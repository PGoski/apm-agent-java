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

import co.elastic.apm.agent.AbstractInstrumentationTest;
import co.elastic.apm.agent.impl.context.Request;
import co.elastic.apm.agent.impl.context.Url;
import co.elastic.apm.agent.impl.transaction.Transaction;
import co.elastic.apm.agent.spring.webflux.testapp.GreetingWebClient;
import co.elastic.apm.agent.spring.webflux.testapp.WebFluxApplication;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class AbstractServerInstrumentationTest extends AbstractInstrumentationTest {

    protected static WebFluxApplication.App app;
    protected GreetingWebClient client;

    @BeforeAll
    static void startApp() {
        app = WebFluxApplication.run(-1, "netty");
    }

    @AfterAll
    static void stopApp() {
        app.close();
    }

    @BeforeEach
    void beforeEach() {
        assertThat(reporter.getTransactions()).isEmpty();
        client = getClient();
    }

    protected abstract GreetingWebClient getClient();

    @Test
    void dispatchError() {
        String error = client.getHandlerError();
        assertThat(error.contains("intentional handler exception"));

        String expectedName = client.useFunctionalEndpoint()
            ? "GET /functional/error-handler"
            : "GreetingAnnotated#handlerError";
        checkTransaction(getFirstTransaction(), expectedName, "GET", 500);
    }

    @Test
    void dispatchHello() {
        client.setHeader("random-value", "12345");
        assertThat(client.getHelloMono()).isEqualTo("Hello, Spring!");

        String expectedName = client.useFunctionalEndpoint()
            ? "GET /functional/hello"
            : "GreetingAnnotated#getHello";
        Transaction transaction = checkTransaction(getFirstTransaction(), expectedName, "GET", 200);

        Request request = transaction.getContext().getRequest();

        checkUrl(transaction, "/hello");

        assertThat(request.getHeaders().getFirst("random-value"))
            .describedAs("non-standard request headers should be captured")
            .isEqualTo("12345");

        assertThat(request.getHeaders().getFirst("Accept"))
            .isEqualTo("text/plain, application/json");

    }

    @Test
    void dispatch404() {
        assertThat(client.getMappingError404()).contains("Not Found");

        Transaction transaction = checkTransaction(getFirstTransaction(), "GET unknown route", "GET", 404);

        assertThat(transaction.getResult()).isEqualTo("HTTP 4xx");
        assertThat(transaction.getContext().getRequest().getMethod()).isEqualTo("GET");
        assertThat(transaction.getContext().getResponse().getStatusCode()).isEqualTo(404);
    }

    @ParameterizedTest
    @CsvSource({"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE"})
    void methodMapping(String method) {
        assertThat(client.methodMapping(method))
            .isEqualTo("HEAD".equals(method) ? "" : String.format("Hello, %s!", method));

        String expectedName;

        if (client.useFunctionalEndpoint()) {
            expectedName = method + " /functional/hello-mapping";
        } else {
            String prefix = method.toLowerCase(Locale.ENGLISH);
            if (Arrays.asList("head", "options", "trace").contains((prefix))) {
                prefix = "other";
            }
            String methodName = prefix + "Mapping";
            expectedName = "GreetingAnnotated#" + methodName;
        }

        checkTransaction(getFirstTransaction(), expectedName, method, 200);
    }

    @Test
    void transactionDuration() {
        // while we can't accurately measure how long transaction takes, we need to ensure that what we measure is
        // at least somehow consistent, thus we test with a comfortable 20% margin
        long duration = 1000;
        assertThat(client.duration(duration))
            .isEqualTo(String.format("Hello, duration=%d!", duration));

        String expectedName = client.useFunctionalEndpoint() ? "GET /functional/duration" : "GreetingAnnotated#duration";
        Transaction transaction = checkTransaction(getFirstTransaction(), expectedName, "GET", 200);
        assertThat(transaction.getDurationMs())
            .isCloseTo(duration * 1d, Offset.offset(200d));

        checkUrl(transaction, "/duration?duration=" + duration);
    }

    @Test
    void shouldInstrumentPathWithParameters() {
        client.withPathParameter("1234");

        String expectedName = client.useFunctionalEndpoint() ? "GET " + client.getPathPrefix() + "/with-parameters/{id}" : "GreetingAnnotated#withParameters";

        Transaction transaction = checkTransaction(getFirstTransaction(), expectedName, "GET", 200);

        checkUrl(transaction, "/with-parameters/1234");

    }

    static void checkUrl(GreetingWebClient client, Transaction transaction, String pathAndQuery) {
        Url url = transaction.getContext().getRequest().getUrl();

        assertThat(url.getProtocol()).isEqualTo("http");
        assertThat(url.getHostname()).isEqualTo("localhost");

        String path = client.getPathPrefix() + pathAndQuery;
        String query = null;
        int queryIndex = path.indexOf('?');
        if (queryIndex >= 0) {
            query = path.substring(queryIndex + 1);
            path = path.substring(0, queryIndex);
        }

        assertThat(url.getPathname()).isEqualTo(path);
        assertThat(url.getSearch()).isEqualTo(query);
        assertThat(url.getPort()).isEqualTo(client.getPort());

        assertThat(url.getFull().toString())
            .isEqualTo(String.format("http://localhost:%d%s%s", client.getPort(), client.getPathPrefix(), pathAndQuery));
    }

    private void checkUrl(Transaction transaction, String pathAndQuery) {
        checkUrl(client, transaction, pathAndQuery);
    }

    protected Transaction getFirstTransaction() {
        return reporter.getFirstTransaction(200);
    }

    static Transaction checkTransaction(Transaction transaction, String expectedName, String expectedMethod, int expectedStatus) {
        assertThat(transaction.getType()).isEqualTo("request");
        assertThat(transaction.getNameAsString()).isEqualTo(expectedName);

        assertThat(transaction.getContext().getRequest().getMethod())
            .isEqualTo(expectedMethod);

        assertThat(transaction.getContext().getResponse().getStatusCode())
            .isEqualTo(expectedStatus);

        assertThat(transaction.getResult())
            .isEqualTo(String.format("HTTP %dxx", expectedStatus / 100));

        return transaction;
    }

}
