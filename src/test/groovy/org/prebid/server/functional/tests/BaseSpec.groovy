package org.prebid.server.functional.tests

import org.prebid.server.functional.repository.HibernateRepositoryService
import org.prebid.server.functional.repository.dao.AccountDao
import org.prebid.server.functional.repository.dao.ConfigDao
import org.prebid.server.functional.repository.dao.StoredImpDao
import org.prebid.server.functional.repository.dao.StoredRequestDao
import org.prebid.server.functional.repository.dao.StoredResponseDao
import org.prebid.server.functional.service.PrebidServerService
import org.prebid.server.functional.testcontainers.Dependencies
import org.prebid.server.functional.testcontainers.PBSTest
import org.prebid.server.functional.testcontainers.PbsServiceFactory
import org.prebid.server.functional.testcontainers.scaffolding.Bidder
import org.prebid.server.functional.testcontainers.scaffolding.PrebidCache
import org.prebid.server.functional.testcontainerswip.ContainerFactory
import org.prebid.server.functional.testcontainerswip.ContainerWrapper
import org.prebid.server.functional.util.ObjectMapperWrapper
import org.prebid.server.functional.util.PBSUtils
import spock.lang.Specification

import static java.math.RoundingMode.DOWN
import static org.prebid.server.functional.util.SystemProperties.DEFAULT_TIMEOUT

@PBSTest
abstract class BaseSpec extends Specification {

    protected static final ObjectMapperWrapper mapper = Dependencies.objectMapperWrapper
    protected static final PbsServiceFactory pbsServiceFactory = new PbsServiceFactory(Dependencies.networkServiceContainer, mapper)
    protected static final Bidder bidder = new Bidder(Dependencies.networkServiceContainer, mapper)
    protected static final PrebidCache prebidCache = new PrebidCache(Dependencies.networkServiceContainer, mapper)
    protected static final PrebidServerService defaultPbsService = pbsServiceFactory.getService([:])

    protected static final HibernateRepositoryService repository = new HibernateRepositoryService(Dependencies.mysqlContainer)
    protected static final AccountDao accountDao = repository.accountDao
    protected static final ConfigDao configDao = repository.configDao
    protected static final StoredImpDao storedImp = repository.storedImpDao
    protected static final StoredRequestDao storedRequestDao = repository.storedRequestDao
    protected static final StoredResponseDao storedResponseDao = repository.storedResponseDao

    protected static final int MAX_TIMEOUT = MIN_TIMEOUT + 1000

    private static final int MIN_TIMEOUT = DEFAULT_TIMEOUT
    private static final int DEFAULT_TARGETING_PRECISION = 1

    private static final ContainerFactory containerFactory = new ContainerFactory(10)
    private final ThreadLocal<Set<ContainerWrapper>> acquiredContainers = ThreadLocal.withInitial { [:] } as ThreadLocal<Set<ContainerWrapper>>

    def setupSpec() {
        prebidCache.setResponse()
        bidder.setResponse()
    }

    def cleanupSpec() {
        bidder.reset()
        prebidCache.reset()
        repository.removeAllDatabaseData()

        releaseContainers()
    }

    def cleanup() {
        releaseContainers()
    }

    protected static int getRandomTimeout() {
        PBSUtils.getRandomNumber(MIN_TIMEOUT, MAX_TIMEOUT)
    }

    protected static Number getCurrentMetricValue(PrebidServerService pbsService = defaultPbsService, String name) {
        def response = pbsService.sendCollectedMetricsRequest()
        response[name] ?: 0
    }

    protected static void flushMetrics(PrebidServerService pbsService = defaultPbsService) {
        // flushing PBS metrics by receiving collected metrics so that each new test works with a fresh state
        pbsService.sendCollectedMetricsRequest()
    }

    protected static List<String> getLogsByText(List<String> logs, String text) {
        logs.findAll { it.contains(text) }
    }

    protected static String getRoundedTargetingValueWithDefaultPrecision(BigDecimal value) {
        "${value.setScale(DEFAULT_TARGETING_PRECISION, DOWN)}0"
    }

    protected acquireContainer(Map<String, String> config) {
        def container = containerFactory.acquireContainer(config)
        acquiredContainers.get().add(container)
        container
    }

    private void releaseContainers() {
        acquiredContainers.get()
                          .each { containerFactory.releaseContainer(it) }
                          .clear()
    }
}
