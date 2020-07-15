/*-
 * #%L
 * Elastic APM Java agent
 * %%
 * Copyright (C) 2018 - 2020 Elastic and contributors
 * %%
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * #L%
 */
package co.elastic.apm.agent.spring.webflux;

import co.elastic.apm.agent.bci.ElasticApmInstrumentation;
import co.elastic.apm.agent.bci.VisibleForAdvice;
import co.elastic.apm.agent.impl.context.Request;
import co.elastic.apm.agent.impl.context.web.ResultUtil;
import co.elastic.apm.agent.impl.transaction.Transaction;
import org.reactivestreams.Subscription;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;

import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static co.elastic.apm.agent.impl.transaction.AbstractSpan.PRIO_HIGH_LEVEL_FRAMEWORK;
import static org.springframework.web.reactive.function.server.RouterFunctions.MATCHING_PATTERN_ATTRIBUTE;

public abstract class WebFluxInstrumentation extends ElasticApmInstrumentation {

    public static final String TRANSACTION_ATTRIBUTE = WebFluxInstrumentation.class.getName() + ".transaction";

    @Override
    public Collection<String> getInstrumentationGroupNames() {
        return Collections.singletonList("spring-webflux");
    }

    /**
     * Activates transaction during "dispatch" phase (before handler execution)
     *
     * @param <T>         mono generic type
     * @param mono        mono to wrap
     * @param transaction transaction
     * @param exchange
     * @return wrapped mono that will activate transaction when mono is used
     */
    @VisibleForAdvice
    public static <T> Mono<T> dispatcherWrap(Mono<T> mono, Transaction transaction, ServerWebExchange exchange) {
        return mono.<T>transform(
            Operators.lift((scannable, subscriber) -> new TransactionAwareSubscriber<T>(subscriber, transaction, false, exchange))
        );
    }

    /**
     * Activates transaction during "handler" phase, where request is handled
     *
     * @param <T>         mono generic type
     * @param mono        mono to wrap
     * @param transaction transaction
     * @param exchange    exchange
     * @return wrapped mono that will activate transaction when mono is used and terminate it on mono is completed
     */
    @VisibleForAdvice
    public static <T> Mono<T> handlerWrap(@Nullable Mono<T> mono, Transaction transaction, ServerWebExchange exchange) {
        return mono.<T>transform(
            Operators.lift((scannable, subscriber) -> new TransactionAwareSubscriber<T>(subscriber, transaction, true, exchange)));
    }

    @VisibleForAdvice
    public static <T> Mono<T> setNameOnComplete(Mono<T> mono, ServerWebExchange exchange) {
        return mono.<T>transform(
            Operators.lift((scannable, subscriber) -> new SubscriberWrapper<T>(subscriber) {

                @Override
                public void onComplete() {
                    super.onComplete();

                    // set transaction name from URL pattern of fallback to request path
                    Transaction transaction = exchange.getAttribute(TRANSACTION_ATTRIBUTE);
                    if (transaction != null) {
                        PathPattern pattern = exchange.getAttribute(MATCHING_PATTERN_ATTRIBUTE);
                        String name = pattern != null ? pattern.getPatternString() : exchange.getRequest().getPath().value();
                        transaction.withName(name, PRIO_HIGH_LEVEL_FRAMEWORK);
                    }

                }

            })
        );
    }

    private static void fillRequest(Transaction transaction, ServerWebExchange exchange) {
        ServerHttpRequest serverRequest = exchange.getRequest();
        Request request = transaction.getContext().getRequest();

        request.withMethod(serverRequest.getMethodValue());

        InetSocketAddress remoteAddress = serverRequest.getRemoteAddress();
        request.getSocket()
            .withRemoteAddress(remoteAddress == null ? null : remoteAddress.getAddress().getHostAddress())
            .withEncrypted(serverRequest.getSslInfo() != null);

        URI uri = serverRequest.getURI();
        request.getUrl()
            .withProtocol(uri.getScheme())
            .withHostname(uri.getHost())
            .withPort(uri.getPort())
            .withPathname(uri.getPath())
            .withSearch(uri.getQuery())
            .updateFull();

        for (Map.Entry<String, List<String>> header : serverRequest.getHeaders().entrySet()) {
            for (String value : header.getValue()) {
                request.getHeaders().add(header.getKey(), value);
            }
        }

        for (Map.Entry<String, List<HttpCookie>> cookie : serverRequest.getCookies().entrySet()) {
            for (HttpCookie value : cookie.getValue()) {
                request.getCookies().add(cookie.getKey(), value.getValue());
            }
        }

    }

    private static void fillResponse(Transaction transaction, ServerWebExchange exchange) {
        HttpStatus statusCode = exchange.getResponse().getStatusCode();
        int status = statusCode != null ? statusCode.value() : 200;

        transaction.withResultIfUnset(ResultUtil.getResultByHttpStatus(status));

        transaction.getContext()
            .getResponse()
            .withFinished(true)
            .withStatusCode(status);
    }

    // TODO if used with servlets, reuse active transaction (if any)

    private static class SubscriberWrapper<T> implements CoreSubscriber<T> {
        private final CoreSubscriber<? super T> subscriber;

        public SubscriberWrapper(CoreSubscriber<? super T> subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public void onSubscribe(Subscription s) {
            subscriber.onSubscribe(s);
        }

        @Override
        public void onNext(T t) {
            subscriber.onNext(t);
        }

        @Override
        public void onError(Throwable t) {
            subscriber.onError(t);
        }

        @Override
        public void onComplete() {
            subscriber.onComplete();
        }
    }

    private static class TransactionAwareSubscriber<T> extends SubscriberWrapper<T> {
        private final Transaction transaction;
        private final boolean terminateTransactionOnComplete;
        private final ServerWebExchange exchange;

        public TransactionAwareSubscriber(CoreSubscriber<? super T> subscriber,
                                          Transaction transaction,
                                          boolean terminateTransactionOnComplete,
                                          ServerWebExchange exchange) {
            super(subscriber);
            this.transaction = transaction;
            this.terminateTransactionOnComplete = terminateTransactionOnComplete;
            this.exchange = exchange;
        }

        @Override
        public void onSubscribe(Subscription s) {
            transaction.activate();
            try {
                super.onSubscribe(s);
            } finally {
                transaction.deactivate();
            }
        }

        @Override
        public void onNext(T next) {
            transaction.activate();
            try {
                super.onNext(next);
            } finally {
                transaction.deactivate();
            }
        }

        @Override
        public void onError(Throwable t) {
            transaction.activate();
            try {
                super.onError(t);
            } finally {
                transaction.deactivate();

                if (t instanceof ResponseStatusException) {
                    // no matching mapping, generates a 404 error
                    HttpStatus status = ((ResponseStatusException) t).getStatus();

                    if (status.value() == 404) {
                        // provide naming consistent with Servlets instrumentation
                        // should override any default name already set
                        StringBuilder transactionName = transaction.getAndOverrideName(PRIO_HIGH_LEVEL_FRAMEWORK, true);
                        if (transactionName != null) {
                            transactionName.append(exchange.getRequest().getMethodValue()).append(" unknown route");
                        }
                    }
                }

                fillRequest(transaction, exchange);
                fillResponse(transaction, exchange);

                transaction.captureException(t)
                    .end();
            }
        }

        @Override
        public void onComplete() {
            transaction.activate();
            try {
                super.onComplete();
            } finally {
                transaction.deactivate();
                if (terminateTransactionOnComplete) {
                    fillRequest(transaction, exchange);
                    fillResponse(transaction, exchange);

                    transaction.end();
                }
            }
        }
    }

}