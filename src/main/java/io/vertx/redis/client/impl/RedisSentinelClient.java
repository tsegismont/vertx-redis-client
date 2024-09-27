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

import io.vertx.core.Completable;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.logging.Logger;
import io.vertx.core.internal.logging.LoggerFactory;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.tracing.TracingPolicy;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.PoolOptions;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisConnectOptions;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.RedisRole;
import io.vertx.redis.client.RedisSentinelConnectOptions;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class RedisSentinelClient extends BaseRedisClient<RedisSentinelConnectOptions> implements Redis {

  // we need some randomness, it doesn't need to be cryptographically secure
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

  private final AtomicReference<SentinelFailover> failover = new AtomicReference<>();

  public RedisSentinelClient(Vertx vertx, NetClientOptions tcpOptions, PoolOptions poolOptions, Supplier<Future<RedisSentinelConnectOptions>> connectOptions, TracingPolicy tracingPolicy) {
    super(vertx, tcpOptions, poolOptions, connectOptions, tracingPolicy);
    // validate options
    if (poolOptions.getMaxWaiting() < poolOptions.getMaxSize()) {
      throw new IllegalStateException("Invalid options: maxWaiting < maxSize");
    }
  }

  @Override
  public Future<RedisConnection> connect() {
    final Promise<RedisConnection> promise = vertx.promise();
    connectOptions.get()
      .onSuccess(opts -> doConnect(opts, promise))
      .onFailure(promise::fail);
    return promise.future();
  }

  private void doConnect(RedisSentinelConnectOptions connectOptions, Completable<RedisConnection> promise) {
    createConnectionInternal(connectOptions, connectOptions.getRole(), (conn, err) -> {
      if (err != null) {
        promise.fail(err);
        return;
      }

      if (connectOptions.getRole() == RedisRole.SENTINEL || connectOptions.getRole() == RedisRole.REPLICA) {
        // it is possible that a replica is later promoted to a master, but that shouldn't be too big of a deal
        promise.succeed(conn);
        return;
      }
      if (!connectOptions.isAutoFailover()) {
        // no auto failover, return the master connection directly
        promise.succeed(conn);
        return;
      }

      SentinelFailover failover = setupFailover(connectOptions);
      RedisSentinelConnection sentinelConn = new RedisSentinelConnection(conn, failover);
      promise.succeed(sentinelConn);
    });
  }

  private SentinelFailover setupFailover(RedisSentinelConnectOptions connectOptions) {
    SentinelFailover result = this.failover.get();

    if (result == null) {
      result = new SentinelFailover(connectOptions.getMasterName(), role -> {
        Promise<PooledRedisConnection> promise = Promise.promise();
        createConnectionInternal(connectOptions, role, promise);
        return promise.future();
      });
      if (this.failover.compareAndSet(null, result)) {
        result.start();
      } else {
        result = this.failover.get();
      }
    }

    return result;
  }

  @Override
  public Future<Void> close() {
    SentinelFailover failover = this.failover.get();
    if (failover != null) {
      failover.close();
    }
    return super.close();
  }

  private void createConnectionInternal(RedisSentinelConnectOptions options, RedisRole role, Completable<PooledRedisConnection> onCreate) {

    final Completable<RedisURI> createAndConnect = (uri, err) -> {
      if (err != null) {
        onCreate.fail(err);
        return;
      }

      final Request setup;

      // `SELECT` is only allowed on non-sentinel nodes
      // we don't send `READONLY` setup to replica nodes, because that's a cluster-only command
      if (role != RedisRole.SENTINEL && uri.select() != null) {
        setup = Request.cmd(Command.SELECT).arg(uri.select());
      } else {
        setup = null;
      }

      // wrap a new client
      connectionManager.getConnection(uri.baseUri(), setup).onComplete(onCreate);
    };

    switch (role) {
      case SENTINEL:
        resolveClient(this::isSentinelOk, options, createAndConnect);
        break;
      case MASTER:
        resolveClient(this::getMasterFromEndpoint, options, createAndConnect);
        break;
      case REPLICA:
        resolveClient(this::getReplicaFromEndpoint, options, createAndConnect);
        break;
    }
  }

  /**
   * We use the algorithm from http://redis.io/topics/sentinel-clients
   * to get a sentinel client and then do 'stuff' with it
   */
  private static void resolveClient(final Resolver checkEndpointFn, final RedisSentinelConnectOptions options, final Completable<RedisURI> callback) {
    // Because finding the master is going to be an async list we will terminate
    // when we find one then use promises...
    iterate(0, ConcurrentHashMap.newKeySet(), checkEndpointFn, options, (found, err) -> {
      if (err != null) {
        callback.fail(err);
      } else {
        // This is the endpoint that has responded so stick it on the top of
        // the list
        final List<String> endpoints = options.getEndpoints();
        String endpoint = endpoints.get(found.left);
        endpoints.set(found.left, endpoints.get(0));
        endpoints.set(0, endpoint);
        // now return the right address
        callback.succeed(found.right);
      }
    });
  }

  private static void iterate(final int idx, final Set<Throwable> failures, final Resolver checkEndpointFn, final RedisSentinelConnectOptions argument, final Completable<Pair<Integer, RedisURI>> resultHandler) {
    // stop condition
    final List<String> endpoints = argument.getEndpoints();

    if (idx >= endpoints.size()) {
      StringBuilder message = new StringBuilder("Cannot connect to any of the provided endpoints");
      for (Throwable failure : failures) {
        message.append("\n- ").append(failure);
      }
      resultHandler.fail(new RedisConnectException(message.toString()));
      return;
    }

    // attempt to perform operation
    checkEndpointFn.resolve(endpoints.get(idx), argument, (res, err) -> {
      if (err == null) {
        resultHandler.succeed(new Pair<>(idx, res));
      } else {
        // try again with next endpoint
        failures.add(err);
        iterate(idx + 1, failures, checkEndpointFn, argument, resultHandler);
      }
    });
  }

  // begin endpoint check methods

  private void isSentinelOk(String endpoint, RedisConnectOptions argument, Completable<RedisURI> handler) {
    // we can't use the endpoint as is, it should not contain a database selection,
    // but can contain authentication
    final RedisURI uri = new RedisURI(endpoint);

    connectionManager.getConnection(uri.baseUri(), null)
      .onFailure(handler::fail)
      .onSuccess(conn -> {
        // Send a command just to check we have a working node
        conn
          .send(Request.cmd(Command.PING))
          .onFailure(handler::fail)
          .onSuccess(ok -> handler.succeed(uri))
          .eventually(() -> conn.close().onFailure(LOG::warn));
      });
  }

  private void getMasterFromEndpoint(String endpoint, RedisSentinelConnectOptions options, Completable<RedisURI> handler) {
    // we can't use the endpoint as is, it should not contain a database selection,
    // but can contain authentication
    final RedisURI uri = new RedisURI(endpoint);
    connectionManager.getConnection(uri.baseUri(), null)
      .onFailure(handler::fail)
      .onSuccess(conn -> {
        final String masterName = options.getMasterName();
        // Send a command just to check we have a working node
        conn
          .send(Request.cmd(Command.SENTINEL).arg("GET-MASTER-ADDR-BY-NAME").arg(masterName))
          .onFailure(handler::fail)
          .onSuccess(response -> {
            if (response == null) {
              handler.fail("Failed to GET-MASTER-ADDR-BY-NAME " + masterName);
            } else {
              final String rHost = response.get(0).toString();
              final Integer rPort = response.get(1).toInteger();
              handler.succeed(new RedisURI(uri, rHost.contains(":") ? "[" + rHost + "]" : rHost, rPort));
            }
          })
          .eventually(() -> conn.close().onFailure(LOG::warn));
      });
  }

  private void getReplicaFromEndpoint(String endpoint, RedisSentinelConnectOptions options, Completable<RedisURI> handler) {
    // we can't use the endpoint as is, it should not contain a database selection,
    // but can contain authentication
    final RedisURI uri = new RedisURI(endpoint);
    connectionManager.getConnection(uri.baseUri(), null)
      .onFailure(handler::fail)
      .onSuccess(conn -> {
        final String masterName = options.getMasterName();
        // Send a command just to check we have a working node
        conn
          .send(Request.cmd(Command.SENTINEL).arg("SLAVES").arg(masterName))
          .onFailure(handler::fail)
          .onSuccess(response -> {
            // Test the response
            if (response == null || response.size() == 0) {
              handler.fail("No replicas linked to the master: " + masterName);
            } else {
              Response replicaInfoArr = response.get(RANDOM.nextInt(response.size()));
              if ((replicaInfoArr.size() % 2) > 0) {
                handler.fail("Corrupted response from the sentinel");
              } else {
                int port = 6379;
                String ip = null;

                if (replicaInfoArr.containsKey("port")) {
                  port = replicaInfoArr.get("port").toInteger();
                }

                if (replicaInfoArr.containsKey("ip")) {
                  ip = replicaInfoArr.get("ip").toString();
                }

                if (ip == null) {
                  handler.fail("No IP found for a REPLICA node!");
                } else {
                  final String host = ip.contains(":") ? "[" + ip + "]" : ip;

                  handler.succeed(new RedisURI(uri, host, port));
                }
              }
            }
          })
          .eventually(() -> conn.close().onFailure(LOG::warn));
      });
  }

}
