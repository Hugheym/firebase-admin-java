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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.api.client.googleapis.batch.BatchCallback;
import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpMethods;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpResponseInterceptor;
import com.google.api.client.http.json.JsonHttpContent;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.JsonObjectParser;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.firebase.ErrorCode;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseException;
import com.google.firebase.ImplFirebaseTrampolines;
import com.google.firebase.IncomingHttpResponse;
import com.google.firebase.OutgoingHttpRequest;
import com.google.firebase.internal.AbstractPlatformErrorHandler;
import com.google.firebase.internal.ApiClientUtils;
import com.google.firebase.internal.ErrorHandlingHttpClient;
import com.google.firebase.internal.HttpRequestInfo;
import com.google.firebase.internal.SdkUtils;
import com.google.firebase.messaging.internal.MessagingServiceErrorResponse;
import com.google.firebase.messaging.internal.MessagingServiceResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * A helper class for interacting with Firebase Cloud Messaging service.
 */
final class FirebaseMessagingClientImpl implements FirebaseMessagingClient {

  private static final String FCM_URL = "https://fcm.googleapis.com/v1/projects/%s/messages:send";

  private static final String FCM_BATCH_URL = "https://fcm.googleapis.com/batch";

  private static final String API_FORMAT_VERSION_HEADER = "X-GOOG-API-FORMAT-VERSION";

  private static final String CLIENT_VERSION_HEADER = "X-Firebase-Client";

  private static final Map<String, String> COMMON_HEADERS =
      ImmutableMap.of(
          API_FORMAT_VERSION_HEADER, "2",
          CLIENT_VERSION_HEADER, "fire-admin-java/" + SdkUtils.getVersion());

  private final String fcmSendUrl;
  private final HttpRequestFactory requestFactory;
  private final HttpRequestFactory childRequestFactory;
  private final JsonFactory jsonFactory;
  private final HttpResponseInterceptor responseInterceptor;
  private final MessagingErrorHandler errorHandler;
  private final ErrorHandlingHttpClient<FirebaseMessagingException> httpClient;

  private FirebaseMessagingClientImpl(Builder builder) {
    checkArgument(!Strings.isNullOrEmpty(builder.projectId));
    this.fcmSendUrl = String.format(FCM_URL, builder.projectId);
    this.requestFactory = checkNotNull(builder.requestFactory);
    this.childRequestFactory = checkNotNull(builder.childRequestFactory);
    this.jsonFactory = checkNotNull(builder.jsonFactory);
    this.responseInterceptor = builder.responseInterceptor;
    this.errorHandler = new MessagingErrorHandler(this.jsonFactory);
    this.httpClient = new ErrorHandlingHttpClient<>(
        this.requestFactory, this.jsonFactory, this.errorHandler);
  }

  @VisibleForTesting
  String getFcmSendUrl() {
    return fcmSendUrl;
  }

  @VisibleForTesting
  HttpRequestFactory getRequestFactory() {
    return requestFactory;
  }

  @VisibleForTesting
  HttpRequestFactory getChildRequestFactory() {
    return childRequestFactory;
  }

  @VisibleForTesting
  JsonFactory getJsonFactory() {
    return jsonFactory;
  }

  public String send(Message message, boolean dryRun) throws FirebaseMessagingException {
    return sendSingleRequest(message, dryRun);
  }

  public BatchResponse sendAll(
      List<Message> messages, boolean dryRun) throws FirebaseMessagingException {
    return sendBatchRequest(messages, dryRun);
  }

  private String sendSingleRequest(
      Message message, boolean dryRun) throws FirebaseMessagingException {
    HttpRequestInfo request =
        HttpRequestInfo.buildPostRequest(
            fcmSendUrl, new JsonHttpContent(jsonFactory, message.wrapForTransport(dryRun)))
            .addAllHeaders(COMMON_HEADERS)
            .setResponseInterceptor(responseInterceptor);
    MessagingServiceResponse parsed = httpClient.sendAndParse(
        request, MessagingServiceResponse.class);
    return parsed.getMessageId();
  }

  private BatchResponse sendBatchRequest(
      List<Message> messages, boolean dryRun) throws FirebaseMessagingException {

    MessagingBatchCallback callback = new MessagingBatchCallback();
    try {
      BatchRequest batch = newBatchRequest(messages, dryRun, callback);
      batch.execute();
      return new BatchResponse(callback.getResponses());
    } catch (HttpResponseException e) {
      OutgoingHttpRequest req = new OutgoingHttpRequest(HttpMethods.POST, FCM_BATCH_URL);
      IncomingHttpResponse resp = new IncomingHttpResponse(e, req);
      throw errorHandler.handleHttpResponseException(e, resp);
    } catch (IOException e) {
      throw errorHandler.handleIOException(e);
    }
  }

  private BatchRequest newBatchRequest(
      List<Message> messages, boolean dryRun, MessagingBatchCallback callback) throws IOException {

    BatchRequest batch = new BatchRequest(
        requestFactory.getTransport(), getBatchRequestInitializer());
    batch.setBatchUrl(new GenericUrl(FCM_BATCH_URL));

    final JsonObjectParser jsonParser = new JsonObjectParser(this.jsonFactory);
    final GenericUrl sendUrl = new GenericUrl(fcmSendUrl);
    for (Message message : messages) {
      // Using a separate request factory without authorization is faster for large batches.
      // A simple performance test showed a 400-500ms speed up for batches of 1000 messages.
      HttpRequest request = childRequestFactory.buildPostRequest(
          sendUrl,
          new JsonHttpContent(jsonFactory, message.wrapForTransport(dryRun)));
      request.setParser(jsonParser);
      request.getHeaders().putAll(COMMON_HEADERS);
      batch.queue(
          request, MessagingServiceResponse.class, MessagingServiceErrorResponse.class, callback);
    }
    return batch;
  }

  private HttpRequestInitializer getBatchRequestInitializer() {
    return new HttpRequestInitializer() {
      @Override
      public void initialize(HttpRequest request) throws IOException {
        HttpRequestInitializer initializer = requestFactory.getInitializer();
        if (initializer != null) {
          initializer.initialize(request);
        }
        request.setResponseInterceptor(responseInterceptor);
      }
    };
  }

  static FirebaseMessagingClientImpl fromApp(FirebaseApp app) {
    String projectId = ImplFirebaseTrampolines.getProjectId(app);
    checkArgument(!Strings.isNullOrEmpty(projectId),
        "Project ID is required to access messaging service. Use a service account credential or "
            + "set the project ID explicitly via FirebaseOptions. Alternatively you can also "
            + "set the project ID via the GOOGLE_CLOUD_PROJECT environment variable.");
    return FirebaseMessagingClientImpl.builder()
        .setProjectId(projectId)
        .setRequestFactory(ApiClientUtils.newAuthorizedRequestFactory(app))
        .setChildRequestFactory(ApiClientUtils.newUnauthorizedRequestFactory(app))
        .setJsonFactory(app.getOptions().getJsonFactory())
        .build();
  }

  static Builder builder() {
    return new Builder();
  }

  static final class Builder {

    private String projectId;
    private HttpRequestFactory requestFactory;
    private HttpRequestFactory childRequestFactory;
    private JsonFactory jsonFactory;
    private HttpResponseInterceptor responseInterceptor;

    private Builder() { }

    Builder setProjectId(String projectId) {
      this.projectId = projectId;
      return this;
    }

    Builder setRequestFactory(HttpRequestFactory requestFactory) {
      this.requestFactory = requestFactory;
      return this;
    }

    Builder setChildRequestFactory(HttpRequestFactory childRequestFactory) {
      this.childRequestFactory = childRequestFactory;
      return this;
    }

    Builder setJsonFactory(JsonFactory jsonFactory) {
      this.jsonFactory = jsonFactory;
      return this;
    }

    Builder setResponseInterceptor(HttpResponseInterceptor responseInterceptor) {
      this.responseInterceptor = responseInterceptor;
      return this;
    }

    FirebaseMessagingClientImpl build() {
      return new FirebaseMessagingClientImpl(this);
    }
  }

  private static class MessagingBatchCallback
      implements BatchCallback<MessagingServiceResponse, MessagingServiceErrorResponse> {

    private final ImmutableList.Builder<SendResponse> responses = ImmutableList.builder();

    @Override
    public void onSuccess(
        MessagingServiceResponse response, HttpHeaders responseHeaders) {
      responses.add(SendResponse.fromMessageId(response.getMessageId()));
    }

    @Override
    public void onFailure(
        MessagingServiceErrorResponse error, HttpHeaders responseHeaders) {

      String status = error.getStatus();
      ErrorCode errorCode = Strings.isNullOrEmpty(status)
          ? ErrorCode.UNKNOWN : Enum.valueOf(ErrorCode.class, status);

      String msg = error.getErrorMessage();
      if (Strings.isNullOrEmpty(msg)) {
        msg = String.format("Unexpected HTTP response: %s", error.toString());
      }

      FirebaseMessagingException exception = new FirebaseMessagingException(errorCode, msg);
      responses.add(SendResponse.fromException(exception));
    }

    List<SendResponse> getResponses() {
      return this.responses.build();
    }
  }

  private static class MessagingErrorHandler
      extends AbstractPlatformErrorHandler<FirebaseMessagingException> {

    private static final Map<String, MessagingErrorCode> MESSAGING_ERROR_CODES =
        ImmutableMap.<String, MessagingErrorCode>builder()
            .put("APNS_AUTH_ERROR", MessagingErrorCode.THIRD_PARTY_AUTH_ERROR)
            .put("INTERNAL", MessagingErrorCode.INTERNAL)
            .put("INVALID_ARGUMENT", MessagingErrorCode.INVALID_ARGUMENT)
            .put("QUOTA_EXCEEDED", MessagingErrorCode.QUOTA_EXCEEDED)
            .put("SENDER_ID_MISMATCH", MessagingErrorCode.SENDER_ID_MISMATCH)
            .put("THIRD_PARTY_AUTH_ERROR", MessagingErrorCode.THIRD_PARTY_AUTH_ERROR)
            .put("UNAVAILABLE", MessagingErrorCode.UNAVAILABLE)
            .put("UNREGISTERED", MessagingErrorCode.UNREGISTERED)
            .build();

    private MessagingErrorHandler(JsonFactory jsonFactory) {
      super(jsonFactory);
    }

    @Override
    protected FirebaseMessagingException createException(ErrorParams params) {
      return new FirebaseMessagingException(
          params.getErrorCode(),
          params.getMessage(),
          params.getException(),
          params.getResponse(),
          getMessagingErrorCode(params.getResponse()));
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
          "Error parsing response from the FCM service: " + e.getMessage(),
          e,
          response,
          null);
    }

    private MessagingErrorCode getMessagingErrorCode(IncomingHttpResponse response) {
      String content = response.getContent();
      if (Strings.isNullOrEmpty(content)) {
        return null;
      }

      try {
        MessagingServiceErrorResponse parsed = jsonFactory.createJsonParser(content)
            .parseAndClose(MessagingServiceErrorResponse.class);
        return MESSAGING_ERROR_CODES.get(parsed.getMessagingErrorCode());
      } catch (IOException ignore) {
        // Ignore any error that may occur while parsing the error response. The server
        // may have responded with a non-json payload.
      }

      return null;
    }
  }
}
