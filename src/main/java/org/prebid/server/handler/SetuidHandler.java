package org.prebid.server.handler;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.RoutingContext;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.analytics.AnalyticsReporterDelegator;
import org.prebid.server.analytics.model.SetuidEvent;
import org.prebid.server.auction.ImplicitParametersExtractor;
import org.prebid.server.auction.IpAddressHelper;
import org.prebid.server.auction.PrivacyEnforcementService;
import org.prebid.server.auction.model.IpAddress;
import org.prebid.server.auction.model.SetuidContext;
import org.prebid.server.bidder.BidderCatalog;
import org.prebid.server.bidder.UsersyncUtil;
import org.prebid.server.bidder.Usersyncer;
import org.prebid.server.cookie.UidsCookie;
import org.prebid.server.cookie.UidsCookieService;
import org.prebid.server.exception.InvalidRequestException;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.exception.UnauthorizedUidsException;
import org.prebid.server.execution.Timeout;
import org.prebid.server.execution.TimeoutFactory;
import org.prebid.server.metric.Metrics;
import org.prebid.server.model.Endpoint;
import org.prebid.server.privacy.gdpr.TcfDefinerService;
import org.prebid.server.privacy.gdpr.model.HostVendorTcfResponse;
import org.prebid.server.privacy.gdpr.model.PrivacyEnforcementAction;
import org.prebid.server.privacy.gdpr.model.TcfContext;
import org.prebid.server.privacy.gdpr.model.TcfResponse;
import org.prebid.server.rubicon.audit.UidsAuditCookieService;
import org.prebid.server.rubicon.audit.proto.UidAudit;
import org.prebid.server.settings.ApplicationSettings;
import org.prebid.server.settings.model.Account;
import org.prebid.server.util.HttpUtil;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class SetuidHandler implements Handler<RoutingContext> {

    private static final Logger logger = LoggerFactory.getLogger(SetuidHandler.class);

    private static final String BIDDER_PARAM = "bidder";
    private static final String UID_PARAM = "uid";
    private static final String PIXEL_FILE_PATH = "static/tracking-pixel.png";
    private static final String ACCOUNT_PARAM = "account";
    private static final int UNAVAILABLE_FOR_LEGAL_REASONS = 451;
    private static final String RUBICON_BIDDER = "rubicon";
    private static final String GDPR_CONSENT_PARAM = "gdpr_consent";

    private final long defaultTimeout;
    private final UidsCookieService uidsCookieService;
    private final ApplicationSettings applicationSettings;
    private final PrivacyEnforcementService privacyEnforcementService;
    private final TcfDefinerService tcfDefinerService;
    private final Integer gdprHostVendorId;
    private final AnalyticsReporterDelegator analyticsDelegator;
    private final Metrics metrics;
    private final TimeoutFactory timeoutFactory;
    private final boolean enableCookie;
    private final UidsAuditCookieService uidsAuditCookieService;
    private final ImplicitParametersExtractor implicitParametersExtractor;
    private final IpAddressHelper ipAddressHelper;
    private final Map<String, String> cookieNameToSyncType;

    public SetuidHandler(long defaultTimeout,
                         UidsCookieService uidsCookieService,
                         ApplicationSettings applicationSettings,
                         BidderCatalog bidderCatalog,
                         PrivacyEnforcementService privacyEnforcementService,
                         TcfDefinerService tcfDefinerService,
                         Integer gdprHostVendorId,
                         AnalyticsReporterDelegator analyticsDelegator,
                         Metrics metrics,
                         TimeoutFactory timeoutFactory,
                         boolean enableCookie,
                         UidsAuditCookieService uidsAuditCookieService,
                         ImplicitParametersExtractor implicitParametersExtractor,
                         IpAddressHelper ipAddressHelper) {

        this.defaultTimeout = defaultTimeout;
        this.uidsCookieService = Objects.requireNonNull(uidsCookieService);
        this.applicationSettings = Objects.requireNonNull(applicationSettings);
        this.privacyEnforcementService = Objects.requireNonNull(privacyEnforcementService);
        this.tcfDefinerService = Objects.requireNonNull(tcfDefinerService);
        this.gdprHostVendorId = validateHostVendorId(gdprHostVendorId);
        this.analyticsDelegator = Objects.requireNonNull(analyticsDelegator);
        this.metrics = Objects.requireNonNull(metrics);
        this.timeoutFactory = Objects.requireNonNull(timeoutFactory);
        this.enableCookie = enableCookie;
        this.uidsAuditCookieService = Objects.requireNonNull(uidsAuditCookieService);
        this.implicitParametersExtractor = Objects.requireNonNull(implicitParametersExtractor);
        this.ipAddressHelper = Objects.requireNonNull(ipAddressHelper);

        cookieNameToSyncType = bidderCatalog.names().stream()
                .filter(bidderCatalog::isActive)
                .map(bidderCatalog::usersyncerByName)
                .distinct() // built-in aliases looks like bidders with the same usersyncers
                .collect(Collectors.toMap(Usersyncer::getCookieFamilyName, SetuidHandler::preferredUserSyncType));
    }

    private static Integer validateHostVendorId(Integer gdprHostVendorId) {
        if (gdprHostVendorId == null) {
            logger.warn("gdpr.host-vendor-id not specified. Will skip host company GDPR checks");
        }
        return gdprHostVendorId;
    }

    private static String preferredUserSyncType(Usersyncer usersyncer) {
        return usersyncer.getPrimaryMethod().getType();
    }

    @Override
    public void handle(RoutingContext routingContext) {
        if (!enableCookie) {
            routingContext.response().setStatusCode(HttpResponseStatus.NO_CONTENT.code()).end();
            return;
        }

        toSetuidContext(routingContext)
                .setHandler(setuidContextResult -> handleSetuidContextResult(setuidContextResult, routingContext));
    }

    private Future<SetuidContext> toSetuidContext(RoutingContext routingContext) {
        final UidsCookie uidsCookie = uidsCookieService.parseFromRequest(routingContext);
        final HttpServerRequest httpRequest = routingContext.request();
        final String cookieName = httpRequest.getParam(BIDDER_PARAM);
        final String requestAccount = httpRequest.getParam(ACCOUNT_PARAM);
        final Timeout timeout = timeoutFactory.create(defaultTimeout);

        return accountById(requestAccount, timeout)
                .compose(account -> privacyEnforcementService.contextFromSetuidRequest(httpRequest, account, timeout,
                        routingContext)
                        .map(privacyContext -> SetuidContext.builder()
                                .routingContext(routingContext)
                                .uidsCookie(uidsCookie)
                                .timeout(timeout)
                                .account(account)
                                .cookieName(cookieName)
                                .syncType(cookieNameToSyncType.get(cookieName))
                                .privacyContext(privacyContext)
                                .build()));
    }

    private Future<Account> accountById(String accountId, Timeout timeout) {
        return StringUtils.isBlank(accountId)
                ? Future.succeededFuture(Account.empty(accountId))
                : applicationSettings.getAccountById(accountId, timeout)
                .otherwise(Account.empty(accountId));
    }

    private void handleSetuidContextResult(AsyncResult<SetuidContext> setuidContextResult,
                                           RoutingContext routingContext) {
        if (setuidContextResult.succeeded()) {
            final SetuidContext setuidContext = setuidContextResult.result();
            final String bidder = setuidContext.getCookieName();
            final TcfContext tcfContext = setuidContext.getPrivacyContext().getTcfContext();

            try {
                validateSetuidContext(setuidContext, bidder);
            } catch (InvalidRequestException | UnauthorizedUidsException e) {
                handleErrors(e, routingContext, tcfContext);
                return;
            }

            isAllowedForHostVendorId(tcfContext)
                    .setHandler(hostTcfResponseResult -> respondByTcfResponse(hostTcfResponseResult, setuidContext));
        } else {
            final Throwable error = setuidContextResult.cause();
            handleErrors(error, routingContext, null);
        }
    }

    private void validateSetuidContext(SetuidContext setuidContext, String bidder) {
        final String cookieName = setuidContext.getCookieName();
        final boolean isCookieNameBlank = StringUtils.isBlank(cookieName);
        if (isCookieNameBlank || !cookieNameToSyncType.containsKey(cookieName)) {
            final String cookieNameError = isCookieNameBlank ? "required" : "invalid";
            throw new InvalidRequestException(String.format("\"bidder\" query param is %s", cookieNameError));
        }

        final TcfContext tcfContext = setuidContext.getPrivacyContext().getTcfContext();
        if (StringUtils.equals(tcfContext.getGdpr(), "1") && BooleanUtils.isFalse(tcfContext.getIsConsentValid())) {
            metrics.updateUserSyncTcfInvalidMetric(bidder);
            throw new InvalidRequestException("Consent string is invalid");
        }

        final UidsCookie uidsCookie = setuidContext.getUidsCookie();
        if (!uidsCookie.allowsSync()) {
            throw new UnauthorizedUidsException("Sync is not allowed for this uids");
        }
    }

    /**
     * If host vendor id is null, host allowed to setuid.
     */
    private Future<HostVendorTcfResponse> isAllowedForHostVendorId(TcfContext tcfContext) {
        return gdprHostVendorId == null
                ? Future.succeededFuture(HostVendorTcfResponse.allowedVendor())
                : tcfDefinerService.resultForVendorIds(Collections.singleton(gdprHostVendorId), tcfContext)
                .map(this::toHostVendorTcfResponse);
    }

    private HostVendorTcfResponse toHostVendorTcfResponse(TcfResponse<Integer> tcfResponse) {
        return HostVendorTcfResponse.of(tcfResponse.getUserInGdprScope(), tcfResponse.getCountry(),
                isSetuidAllowed(tcfResponse));
    }

    private boolean isSetuidAllowed(TcfResponse<Integer> hostTcfResponseToSetuidContext) {
        // allow cookie only if user is not in GDPR scope or vendor passed GDPR check
        final boolean notInGdprScope = BooleanUtils.isFalse(hostTcfResponseToSetuidContext.getUserInGdprScope());

        final Map<Integer, PrivacyEnforcementAction> vendorIdToAction = hostTcfResponseToSetuidContext.getActions();
        final PrivacyEnforcementAction hostPrivacyAction = vendorIdToAction != null
                ? vendorIdToAction.get(gdprHostVendorId)
                : null;
        final boolean blockPixelSync = hostPrivacyAction == null || hostPrivacyAction.isBlockPixelSync();

        return notInGdprScope || !blockPixelSync;
    }

    private void respondByTcfResponse(AsyncResult<HostVendorTcfResponse> hostTcfResponseResult,
                                      SetuidContext setuidContext) {
        final String bidderCookieName = setuidContext.getCookieName();
        final TcfContext tcfContext = setuidContext.getPrivacyContext().getTcfContext();
        final RoutingContext routingContext = setuidContext.getRoutingContext();

        if (hostTcfResponseResult.succeeded()) {
            final HostVendorTcfResponse hostTcfResponse = hostTcfResponseResult.result();
            if (hostTcfResponse.isVendorAllowed()) {

                if (bidderCookieName.equals(RUBICON_BIDDER)) {
                    respondForRubiconBidder(setuidContext, hostTcfResponse);
                } else {
                    respondForOtherBidder(setuidContext, hostTcfResponse);
                }

            } else {
                metrics.updateUserSyncTcfBlockedMetric(bidderCookieName);

                final HttpResponseStatus status = new HttpResponseStatus(UNAVAILABLE_FOR_LEGAL_REASONS,
                        "Unavailable for legal reasons");

                HttpUtil.executeSafely(routingContext, Endpoint.setuid,
                        response -> response
                                .setStatusCode(status.code())
                                .setStatusMessage(status.reasonPhrase())
                                .end("The gdpr_consent param prevents cookies from being saved"));

                analyticsDelegator.processEvent(SetuidEvent.error(status.code()), tcfContext);
            }

        } else {
            final Throwable error = hostTcfResponseResult.cause();
            metrics.updateUserSyncTcfBlockedMetric(bidderCookieName);
            handleErrors(error, routingContext, tcfContext);
        }
    }

    private void respondForRubiconBidder(SetuidContext setuidContext, HostVendorTcfResponse hostTcfResponse) {
        final Cookie uidsAuditCookie;

        final TcfContext tcfContext = setuidContext.getPrivacyContext().getTcfContext();
        final RoutingContext routingContext = setuidContext.getRoutingContext();
        if (BooleanUtils.isTrue(hostTcfResponse.getUserInGdprScope())) {

            final String account = setuidContext.getAccount().getId();
            if (StringUtils.isBlank(account)) {
                final int status = HttpResponseStatus.BAD_REQUEST.code();
                respondWith(routingContext, status, "\"account\" query param is required");
                metrics.updateUserSyncBadRequestMetric();
                analyticsDelegator.processEvent(SetuidEvent.error(status), tcfContext);
                return;
            }

            try {
                final HttpServerRequest request = routingContext.request();
                final String uid = request.getParam(UID_PARAM);
                final String gdprConsent = request.getParam(GDPR_CONSENT_PARAM);
                final String ip = resolveIpFromRequest(request);
                final String country = hostTcfResponse.getCountry();
                uidsAuditCookie = uidsAuditCookieService
                        .createUidsAuditCookie(routingContext, uid, account, gdprConsent, country, ip);
            } catch (PreBidException e) {
                respondWithUidAuditCreationError(routingContext, e, tcfContext);
                return;
            }
        } else {
            uidsAuditCookie = null;
        }

        respondWithCookie(setuidContext, uidsAuditCookie);
    }

    private String resolveIpFromRequest(HttpServerRequest request) {
        final List<String> requestIps =
                implicitParametersExtractor.ipFrom(request.headers(), request.remoteAddress().host());
        return requestIps.stream()
                .map(ipAddressHelper::toIpAddress)
                .filter(Objects::nonNull)
                .map(IpAddress::getIp)
                .findFirst()
                .orElse(null);
    }

    private void respondForOtherBidder(SetuidContext setuidContext, HostVendorTcfResponse hostTcfResponse) {
        final Cookie uidsAuditCookie;

        final TcfContext tcfContext = setuidContext.getPrivacyContext().getTcfContext();
        final RoutingContext routingContext = setuidContext.getRoutingContext();
        if (BooleanUtils.isTrue(hostTcfResponse.getUserInGdprScope())) {
            final UidAudit uidsAudit;
            try {
                uidsAudit = uidsAuditCookieService.getUidsAudit(routingContext);
            } catch (PreBidException e) {
                final int status = HttpResponseStatus.BAD_REQUEST.code();
                final String message = String.format("Error retrieving of uids-audit cookie: %s", e.getMessage());
                respondWith(routingContext, status, message);
                metrics.updateUserSyncBadRequestMetric();
                logger.info(message);
                analyticsDelegator.processEvent(SetuidEvent.error(status), tcfContext);
                return;
            }

            if (uidsAudit == null) {
                final int status = HttpResponseStatus.BAD_REQUEST.code();
                respondWith(routingContext, status, "\"uids-audit\" cookie is missing, sync Rubicon bidder first");
                metrics.updateUserSyncBadRequestMetric();
                analyticsDelegator.processEvent(SetuidEvent.error(status), tcfContext);
                return;
            }

            try {
                final HttpServerRequest request = routingContext.request();
                final String uid = request.getParam(UID_PARAM);
                final String gdprConsent = request.getParam(GDPR_CONSENT_PARAM);
                final String ip = resolveIpFromRequest(request);
                final String initiatorId = uidsAudit.getInitiatorId();
                final String country = hostTcfResponse.getCountry();
                uidsAuditCookie = uidsAuditCookieService
                        .createUidsAuditCookie(routingContext, uid, initiatorId, gdprConsent, country, ip);
            } catch (PreBidException e) {
                respondWithUidAuditCreationError(routingContext, e, tcfContext);
                return;
            }
        } else {
            uidsAuditCookie = null;
        }

        respondWithCookie(setuidContext, uidsAuditCookie);
    }

    private void respondWithUidAuditCreationError(RoutingContext routingContext, PreBidException e,
                                                  TcfContext tcfContext) {

        final int status = HttpResponseStatus.BAD_REQUEST.code();
        final String message = String.format("Error occurred on uids-audit cookie creation, "
                + "uid cookie will not be set without it: %s", e.getMessage());
        respondWith(routingContext, status, message);
        metrics.updateUserSyncBadRequestMetric();
        logger.info(message);
        analyticsDelegator.processEvent(SetuidEvent.error(status), tcfContext);
    }

    private void respondWithCookie(SetuidContext setuidContext, Cookie uidsAuditCookie) {
        final RoutingContext routingContext = setuidContext.getRoutingContext();
        final String uid = routingContext.request().getParam(UID_PARAM);
        final UidsCookie updatedUidsCookie;
        boolean successfullyUpdated = false;

        final String bidder = setuidContext.getCookieName();
        final UidsCookie uidsCookie = setuidContext.getUidsCookie();
        if (StringUtils.isBlank(uid)) {
            updatedUidsCookie = uidsCookie.deleteUid(bidder);
        } else if (UidsCookie.isFacebookSentinel(bidder, uid)) {
            // At the moment, Facebook calls /setuid with a UID of 0 if the user isn't logged into Facebook.
            // They shouldn't be sending us a sentinel value... but since they are, we're refusing to save that ID.
            updatedUidsCookie = uidsCookie;
        } else {
            updatedUidsCookie = uidsCookie.updateUid(bidder, uid);
            successfullyUpdated = true;
            metrics.updateUserSyncSetsMetric(bidder);
        }

        final Cookie cookie = uidsCookieService.toCookie(updatedUidsCookie);
        addCookie(routingContext, cookie);

        if (uidsAuditCookie != null) {
            addCookie(routingContext, uidsAuditCookie);
        }

        final HttpResponseStatus status = HttpResponseStatus.OK;

        final String format = routingContext.request().getParam(UsersyncUtil.FORMAT_PARAMETER);
        if (shouldRespondWithPixel(format, setuidContext.getSyncType())) {
            HttpUtil.executeSafely(routingContext, Endpoint.setuid,
                    response -> response
                            .sendFile(PIXEL_FILE_PATH));
        } else {
            HttpUtil.executeSafely(routingContext, Endpoint.setuid,
                    response -> response
                            .setStatusCode(status.code())
                            .putHeader(HttpHeaders.CONTENT_LENGTH, "0")
                            .putHeader(HttpHeaders.CONTENT_TYPE, HttpHeaders.TEXT_HTML)
                            .end());
        }

        final TcfContext tcfContext = setuidContext.getPrivacyContext().getTcfContext();
        analyticsDelegator.processEvent(SetuidEvent.builder()
                .status(status.code())
                .bidder(bidder)
                .uid(uid)
                .success(successfullyUpdated)
                .build(), tcfContext);
    }

    private boolean shouldRespondWithPixel(String format, String syncType) {
        return StringUtils.equals(format, UsersyncUtil.IMG_FORMAT)
                || (!StringUtils.equals(format, UsersyncUtil.BLANK_FORMAT)
                && StringUtils.equals(syncType, Usersyncer.UsersyncMethod.REDIRECT_TYPE));
    }

    private void handleErrors(Throwable error, RoutingContext routingContext, TcfContext tcfContext) {
        final String message = error.getMessage();
        final HttpResponseStatus status;
        final String body;
        if (error instanceof InvalidRequestException) {
            metrics.updateUserSyncBadRequestMetric();
            status = HttpResponseStatus.BAD_REQUEST;
            body = String.format("Invalid request format: %s", message);
        } else if (error instanceof UnauthorizedUidsException) {
            metrics.updateUserSyncOptoutMetric();
            status = HttpResponseStatus.UNAUTHORIZED;
            body = String.format("Unauthorized: %s", message);
        } else {
            status = HttpResponseStatus.INTERNAL_SERVER_ERROR;
            body = String.format("Unexpected setuid processing error: %s", message);
            logger.warn(body, error);
        }

        HttpUtil.executeSafely(routingContext, Endpoint.setuid,
                response -> response
                        .setStatusCode(status.code())
                        .end(body));

        final SetuidEvent setuidEvent = SetuidEvent.error(status.code());
        if (tcfContext == null) {
            analyticsDelegator.processEvent(setuidEvent);
        } else {
            analyticsDelegator.processEvent(setuidEvent, tcfContext);
        }
    }

    private void addCookie(RoutingContext routingContext, Cookie cookie) {
        routingContext.response().headers().add(HttpUtil.SET_COOKIE_HEADER, HttpUtil.toSetCookieHeaderValue(cookie));
    }

    private static void respondWith(RoutingContext routingContext, int status, String body) {
        HttpUtil.executeSafely(routingContext, Endpoint.setuid,
                response -> response
                        .setStatusCode(status)
                        .putHeader(HttpHeaders.CONTENT_LENGTH, "0")
                        .putHeader(HttpHeaders.CONTENT_TYPE, HttpHeaders.TEXT_HTML)
                        .end(body));
    }
}
