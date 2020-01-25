/*
 * Copyright 2019 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.firebase.messaging;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponseInterceptor;
import com.google.api.client.http.json.JsonHttpContent;
import com.google.api.client.json.GenericJson;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.util.Key;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.firebase.ErrorCode;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseException;
import com.google.firebase.IncomingHttpResponse;
import com.google.firebase.internal.AbstractHttpErrorHandler;
import com.google.firebase.internal.ApiClientUtils;
import com.google.firebase.internal.ErrorHandlingHttpClient;
import com.google.firebase.internal.HttpRequestInfo;
import com.google.firebase.internal.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * A helper class for interacting with the Firebase Instance ID service. Implements the FCM
 * topic management functionality.
 */
final class InstanceIdClientImpl implements InstanceIdClient {

  private static final String IID_HOST = "https://iid.googleapis.com";

  private static final String IID_SUBSCRIBE_PATH = "iid/v1:batchAdd";

  private static final String IID_UNSUBSCRIBE_PATH = "iid/v1:batchRemove";

  private final ErrorHandlingHttpClient<FirebaseMessagingException> requestFactory;
  private final JsonFactory jsonFactory;
  private final HttpResponseInterceptor responseInterceptor;

  InstanceIdClientImpl(HttpRequestFactory requestFactory, JsonFactory jsonFactory) {
    this(requestFactory, jsonFactory, null);
  }

  InstanceIdClientImpl(
      HttpRequestFactory requestFactory,
      JsonFactory jsonFactory,
      @Nullable HttpResponseInterceptor responseInterceptor) {
    this.requestFactory = new ErrorHandlingHttpClient<>(
        requestFactory, jsonFactory, new InstanceIdErrorHandler(jsonFactory));
    this.jsonFactory = checkNotNull(jsonFactory);
    this.responseInterceptor = responseInterceptor;
  }

  static InstanceIdClientImpl fromApp(FirebaseApp app) {
    return new InstanceIdClientImpl(
        ApiClientUtils.newAuthorizedRequestFactory(app),
        app.getOptions().getJsonFactory());
  }

  @VisibleForTesting
  JsonFactory getJsonFactory() {
    return jsonFactory;
  }

  public TopicManagementResponse subscribeToTopic(
      String topic, List<String> registrationTokens) throws FirebaseMessagingException {
    return sendInstanceIdRequest(topic, registrationTokens, IID_SUBSCRIBE_PATH);
  }

  public TopicManagementResponse unsubscribeFromTopic(
      String topic, List<String> registrationTokens) throws FirebaseMessagingException {
    return sendInstanceIdRequest(topic, registrationTokens, IID_UNSUBSCRIBE_PATH);
  }

  private TopicManagementResponse sendInstanceIdRequest(
      String topic,
      List<String> registrationTokens,
      String path) throws FirebaseMessagingException {

    String url = String.format("%s/%s", IID_HOST, path);
    Map<String, Object> payload = ImmutableMap.of(
        "to", getPrefixedTopic(topic),
        "registration_tokens", registrationTokens
    );

    HttpRequestInfo request =
        HttpRequestInfo.buildPostRequest(url, new JsonHttpContent(jsonFactory, payload))
            .addHeader("access_token_auth", "true")
            .setResponseInterceptor(responseInterceptor);
    InstanceIdServiceResponse response = new InstanceIdServiceResponse();
    requestFactory.sendAndParse(request, response);
    return new TopicManagementResponse(response.results);
  }

  private String getPrefixedTopic(String topic) {
    if (topic.startsWith("/topics/")) {
      return topic;
    } else {
      return "/topics/" + topic;
    }
  }

  private static class InstanceIdServiceResponse {
    @Key("results")
    private List<GenericJson> results;
  }

  private static class InstanceIdServiceErrorResponse {
    @Key("error")
    private String error;
  }

  private static class InstanceIdErrorHandler
      extends AbstractHttpErrorHandler<FirebaseMessagingException> {

    private final JsonFactory jsonFactory;

    InstanceIdErrorHandler(JsonFactory jsonFactory) {
      this.jsonFactory = jsonFactory;
    }

    @Override
    protected FirebaseMessagingException createException(ErrorParams params) {
      return new FirebaseMessagingException(
          params.getErrorCode(),
          this.getErrorMessage(params),
          params.getException(),
          params.getResponse(),
          null);
    }

    @Override
    public FirebaseMessagingException handleIOException(IOException e) {
      FirebaseException error = ApiClientUtils.newFirebaseException(e);
      return new FirebaseMessagingException(error.getErrorCodeNew(), error.getMessage(), e);
    }

    @Override
    public FirebaseMessagingException handleParseException(
        IOException e, IncomingHttpResponse response) {
      return new FirebaseMessagingException(
          ErrorCode.UNKNOWN,
          "Error parsing response from the topic management service: " + e.getMessage(),
          e,
          response,
          null);
    }

    private String getErrorMessage(ErrorParams params) {
      String content = params.getResponse().getContent();
      if (!Strings.isNullOrEmpty(content)) {
        try {
          InstanceIdServiceErrorResponse response = new InstanceIdServiceErrorResponse();
          jsonFactory.createJsonParser(content).parse(response);
          if (!Strings.isNullOrEmpty(response.error)) {
            return response.error;
          }
        } catch (IOException ignore) {
          // Ignore any error that may occur while parsing the error response. The server
          // may have responded with a non-json payload.
        }
      }

      return params.getMessage();
    }
  }
}
