package org.dominokit.domino.rest.shared.request;

import org.dominokit.domino.api.shared.extension.ContextAggregator;
import org.dominokit.domino.rest.shared.Response;
import org.dominokit.domino.rest.shared.RestfulRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static java.util.Objects.nonNull;

public class RequestSender<R, S> implements RequestRestSender<R, S> {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestSender.class);

    private final List<String> SEND_BODY_METHODS = Arrays.asList("POST", "PUT", "PATCH");

    @Override
    public void send(ServerRequest<R, S> request, ServerRequestCallBack callBack) {
        request.normalizeUrl();
        List<RequestInterceptor> interceptors = DominoRestContext.make().getConfig().getRequestInterceptors();

        if (nonNull(interceptors) && !interceptors.isEmpty()) {
            List<InterceptorRequestWait> interceptorsWaitList = interceptors.stream().map(InterceptorRequestWait::new)
                    .collect(Collectors.toList());
            ContextAggregator.waitFor(interceptorsWaitList)
                    .onReady(() -> onAfterInterception(request, callBack));
            interceptorsWaitList.forEach(interceptorWait -> interceptorWait.getInterceptor().interceptRequest(request, interceptorWait));
        } else {
            onAfterInterception(request, callBack);
        }
    }

    private void onAfterInterception(ServerRequest<R, S> request, ServerRequestCallBack callBack) {
        final int[] retriesCounter = new int[]{0};
        RestfulRequest restfulRequest = RestfulRequest.request(request.getUrl(), request.getHttpMethod().toUpperCase());
        restfulRequest
                .putHeaders(request.headers())
                .putParameters(request.parameters())
                .onSuccess(response -> handleResponse(request, callBack, response))
                .onError(throwable -> handleError(request, callBack, retriesCounter, restfulRequest, throwable));

        if (nonNull(request.getResponseType())) {
            restfulRequest.setResponseType(request.getResponseType());
        }

        setTimeout(request, restfulRequest);
        doSendRequest(request, restfulRequest);
    }

    private void handleError(ServerRequest<R, S> request, ServerRequestCallBack callBack, int[] retriesCounter, RestfulRequest restfulRequest, Throwable throwable) {
        if (throwable instanceof RequestTimeoutException && retriesCounter[0] < request.getMaxRetries()) {
            retriesCounter[0]++;
            LOGGER.info("Retrying request : " + retriesCounter[0]);
            doSendRequest(request, restfulRequest);
        } else {
            FailedResponseBean failedResponse = new FailedResponseBean(throwable);
            LOGGER.info("Failed to execute request : ", failedResponse);
            callFailedResponseHandlers(request, failedResponse);
            callBack.onFailure(failedResponse);
        }
    }

    private void handleResponse(ServerRequest<R, S> request, ServerRequestCallBack callBack, Response response) {
        if (Arrays.stream(request.getSuccessCodes()).anyMatch(code -> code.equals(response.getStatusCode()))) {
            callSuccessGlobalHandlers(request, response);
            callBack.onSuccess(request.getResponseReader().read(response));
        } else {
            FailedResponseBean failedResponse = new FailedResponseBean(response.getStatusCode(), response.getStatusText(), response.getBodyAsString(), response.getHeaders());
            callFailedResponseHandlers(request, failedResponse);
            callBack.onFailure(failedResponse);
        }
    }

    private void callSuccessGlobalHandlers(ServerRequest<R, S> request, Response response) {
        DominoRestContext.make().getConfig()
                .getResponseInterceptors()
                .forEach(responseInterceptor -> responseInterceptor.interceptOnSuccess(request, response));
    }

    private void callFailedResponseHandlers(ServerRequest request, FailedResponseBean failedResponse) {
        DominoRestContext.make().getConfig()
                .getResponseInterceptors()
                .forEach(responseInterceptor -> responseInterceptor.interceptOnFailed(request, failedResponse));
    }

    private void setTimeout(ServerRequest<R, S> request, RestfulRequest restfulRequest) {
        if (request.getTimeout() > 0) {
            restfulRequest.timeout(request.getTimeout());
        }
    }

    private void doSendRequest(ServerRequest<R, S> request, RestfulRequest restfulRequest) {
        if (SEND_BODY_METHODS.contains(request.getHttpMethod().toUpperCase()) && !request.isVoidRequest()) {
            restfulRequest.send(request.getRequestWriter().write(request.requestBean()));
        } else {
            restfulRequest.send();
        }
    }
}

