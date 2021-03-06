/*
 * Copyright 2019 Red Hat, Inc.
 * <p>
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 * <p>
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * <p>
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 * <p>
 * You may elect to redistribute this code under either of these licenses.
 */
package io.vertx.redis.client.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.redis.client.*;
import io.vertx.redis.client.impl.types.ErrorType;

import java.util.List;
import java.util.Random;

import static io.vertx.redis.client.Command.*;
import static io.vertx.redis.client.Request.cmd;

public class RedisSentinelClient implements Redis {

  // We don't need to be secure, we just want so simple
  // randomization to avoid picking the same slave all the time
  private static final Random RANDOM = new Random();

  private static class Pair<L, R> {
    final L left;
    final R right;

    Pair(L left, R right) {
      this.left = left;
      this.right = right;
    }
  }

  private static final Logger LOG = LoggerFactory.getLogger(RedisSentinelClient.class);

  private final Vertx vertx;
  private final RedisOptions options;

  private Redis sentinel;
  private RedisClient redis;

  private RedisSentinelClient(Vertx vertx, RedisOptions options) {
    this.vertx = vertx;
    this.options = options;
  }

  @Override
  public Redis connect(Handler<AsyncResult<Redis>> onCreate) {
    // sentinel (HA) requires 2 connections, one to watch for sentinel events and the connection itself
    createClientInternal(vertx, options, RedisRole.SENTINEL, create -> {
      if (create.failed()) {
        LOG.error("Redis PUB/SUB wrap failed.", create.cause());
        return;
      }

      sentinel = create.result();

      sentinel
        .handler(msg -> {
          if (msg.type() == ResponseType.MULTI) {
            if ("MESSAGE".equalsIgnoreCase(msg.get(0).toString())) {
              // we don't care about the payload
              if (redis != null) {
                redis.fail(ErrorType.create("SWITCH-MASTER Received +switch-master message from Redis Sentinel."));
              } else {
                LOG.warn("Received +switch-master message from Redis Sentinel.");
              }
            }
          }
        });

      sentinel.send(cmd(SUBSCRIBE).arg("+switch-master"), send -> {
        if (send.failed()) {
          LOG.error("Unable to subscribe to Sentinel PUBSUB", send.cause());
          sentinel.close();
        }
      });

      sentinel.exceptionHandler(t -> {
        LOG.error("Unhandled exception in Sentinel PUBSUB", t);
        sentinel.close();
      });
    });

    createClientInternal(vertx, options, options.getRole(), create -> {
      if (create.failed()) {
        onCreate.handle(create);
        return;
      }

      redis = (RedisClient) create.result();

      onCreate.handle(Future.succeededFuture(this));
    });

    return this;
  }

  @Override
  public void close() {
    sentinel.close();
    redis.close();
  }

  @Override
  public Redis exceptionHandler(Handler<Throwable> handler) {
    redis.exceptionHandler(handler);
    return this;
  }

  @Override
  public Redis endHandler(Handler<Void> handler) {
    redis.endHandler(handler);
    return this;
  }

  @Override
  public Redis handler(Handler<Response> handler) {
    redis.handler(handler);
    return this;
  }

  @Override
  public Redis pause() {
    redis.pause();
    return this;
  }

  @Override
  public Redis resume() {
    redis.resume();
    return null;
  }

  @Override
  public Redis send(Request command, Handler<AsyncResult<Response>> handler) {
    redis.send(command, handler);
    return this;
  }

  @Override
  public Redis batch(List<Request> commands, Handler<AsyncResult<List<Response>>> handler) {
    redis.batch(commands, handler);
    return this;
  }

  @Override
  public String socketAddress() {
    return redis.socketAddress();
  }

  @Override
  public Redis fetch(long amount) {
    redis.fetch(amount);
    return this;
  }

  public static Redis create(Vertx vertx, RedisOptions options) {
    return new RedisSentinelClient(vertx, options);
  }

  private static void createClientInternal(Vertx vertx, RedisOptions options, RedisRole role, Handler<AsyncResult<Redis>> onCreate) {

    final Handler<AsyncResult<String>> createAndConnect = resolve -> {
      if (resolve.failed()) {
        onCreate.handle(Future.failedFuture(resolve.cause()));
        return;
      }
      // wrap a new client
      RedisClient.create(vertx, options, resolve.result()).connect(onCreate);
    };

    switch (role) {
      case SENTINEL:
        resolveClient(vertx, RedisSentinelClient::isSentinelOk, options, createAndConnect);
        break;

      case MASTER:
        resolveClient(vertx, RedisSentinelClient::getMasterFromEndpoint, options, createAndConnect);
        break;

      case SLAVE:
        resolveClient(vertx, RedisSentinelClient::getSlaveFromEndpoint, options, createAndConnect);
    }
  }

  /**
   * We use the algorithm from http://redis.io/topics/sentinel-clients
   * to get a sentinel client and then do 'stuff' with it
   */
  private static void resolveClient(final Vertx vertx, final Resolver checkEndpointFn, final RedisOptions options, final Handler<AsyncResult<String>> callback) {
    // Because finding the master is going to be an async list we will terminate
    // when we find one then use promises...
    iterate(0, vertx, checkEndpointFn, options, iterate -> {
      if (iterate.failed()) {
        callback.handle(Future.failedFuture(iterate.cause()));
      } else {
        final Pair<Integer, String> found = iterate.result();
        // This is the endpoint that has responded so stick it on the top of
        // the list
        final List<String> endpoints = options.getEndpoints();
        String endpoint = endpoints.get(found.left);
        endpoints.set(found.left, endpoints.get(0));
        endpoints.set(0, endpoint);
        // now return the right address
        callback.handle(Future.succeededFuture(found.right));
      }
    });
  }

  private static void iterate(final int idx, final Vertx vertx, final Resolver checkEndpointFn, final RedisOptions argument, final Handler<AsyncResult<Pair<Integer, String>>> resultHandler) {
    // stop condition
    final List<String> endpoints = argument.getEndpoints();

    if (idx >= endpoints.size()) {
      resultHandler.handle(Future.failedFuture("No more endpoints in chain."));
      return;
    }

    // attempt to perform operation
    checkEndpointFn.resolve(vertx, endpoints.get(idx), argument, res -> {
      if (res.succeeded()) {
        resultHandler.handle(Future.succeededFuture(new Pair<>(idx, res.result())));
      } else {
        // try again with next endpoint
        iterate(idx + 1, vertx, checkEndpointFn, argument, resultHandler);
      }
    });
  }

  // begin endpoint check methods

  private static void isSentinelOk(Vertx vertx, String endpoint, RedisOptions argument, Handler<AsyncResult<String>> handler) {

    RedisClient.create(vertx, argument, endpoint).connect(onCreate -> {
      if (onCreate.failed()) {
        handler.handle(Future.failedFuture(onCreate.cause()));
        return;
      }

      final Redis conn = onCreate.result();

      // Send a command just to check we have a working node
      conn.send(cmd(PING), info -> {
        if (info.failed()) {
          handler.handle(Future.failedFuture(info.cause()));
          return;
        }

        handler.handle(Future.succeededFuture(endpoint));
        conn.close();
      });
    });
  }

  private static void getMasterFromEndpoint(Vertx vertx, String endpoint, RedisOptions options, Handler<AsyncResult<String>> handler) {
    RedisClient.create(vertx, options, endpoint).connect(onCreate -> {
      if (onCreate.failed()) {
        handler.handle(Future.failedFuture(onCreate.cause()));
        return;
      }

      final Redis conn = onCreate.result();
      final String masterName = options.getMasterName();

      // Send a command just to check we have a working node
      conn.send(cmd(SENTINEL).arg("GET-MASTER-ADDR-BY-NAME").arg(masterName), getMasterAddrByName -> {
        if (getMasterAddrByName.failed()) {
          handler.handle(Future.failedFuture(getMasterAddrByName.cause()));
          return;
        }

        // Test the response
        final Response response = getMasterAddrByName.result();

        handler.handle(
          Future.succeededFuture("redis://" + response.get(0).toString() + ":" + response.get(1).toInteger()));

        // we don't need this connection anymore
        conn.close();
      });
    });
  }

  private static void getSlaveFromEndpoint(Vertx vertx, String endpoint, RedisOptions options, Handler<AsyncResult<String>> handler) {
    RedisClient.create(vertx, options, endpoint).connect(onCreate -> {
      if (onCreate.failed()) {
        handler.handle(Future.failedFuture(onCreate.cause()));
        return;
      }

      final Redis conn = onCreate.result();
      final String masterName = options.getMasterName();

      // Send a command just to check we have a working node
      conn.send(cmd(SENTINEL).arg("SLAVES").arg(masterName), sentinelSlaves -> {
        if (sentinelSlaves.failed()) {
          handler.handle(Future.failedFuture(sentinelSlaves.cause()));
          return;
        }

        final Response response = sentinelSlaves.result();

        // Test the response
        if (response.size() == 0) {
          handler.handle(Future.failedFuture("No slaves linked to the master: " + masterName));
        } else {
          Response slaveInfoArr = response.get(RANDOM.nextInt(response.size()));
          if ((slaveInfoArr.size() % 2) > 0) {
            handler.handle(Future.failedFuture("Corrupted response from the sentinel"));
          } else {
            int port = 6379;
            String ip = null;

            for (int i = 0; i < slaveInfoArr.size(); i += 2) {
              if ("port".equals(slaveInfoArr.get(i).toString())) {
                port = slaveInfoArr.get(i + 1).toInteger();
              }
              if ("ip".equals(slaveInfoArr.get(i).toString())) {
                ip = slaveInfoArr.get(i + 1).toString();
              }
            }

            if (ip == null) {
              handler.handle(Future.failedFuture("No IP found for a SLAVE node!"));
            } else {
              handler.handle(Future.succeededFuture("redis://" + ip + ":" + port));
            }
          }
        }
        conn.close();
      });
    });
  }
}
