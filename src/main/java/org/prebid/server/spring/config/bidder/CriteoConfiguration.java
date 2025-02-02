package org.prebid.server.spring.config.bidder;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.prebid.server.bidder.BidderDeps;
import org.prebid.server.bidder.criteo.CriteoBidder;
import org.prebid.server.identity.NoneIdGenerator;
import org.prebid.server.identity.UUIDIdGenerator;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.spring.config.bidder.model.BidderConfigurationProperties;
import org.prebid.server.spring.config.bidder.util.BidderDepsAssembler;
import org.prebid.server.spring.config.bidder.util.UsersyncerCreator;
import org.prebid.server.spring.env.YamlPropertySourceFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.validation.annotation.Validated;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Configuration
@PropertySource(value = "classpath:/bidder-config/criteo.yaml", factory = YamlPropertySourceFactory.class)
public class CriteoConfiguration {

    private static final String BIDDER_NAME = "criteo";

    @Bean("criteoConfigurationProperties")
    @ConfigurationProperties("adapters.criteo")
    CriteoConfigurationProperties configurationProperties() {
        return new CriteoConfigurationProperties();
    }

    @Bean
    BidderDeps criteoBidderDeps(CriteoConfigurationProperties criteoConfigurationProperties,
                                @NotBlank @Value("${external-url}") String externalUrl,
                                JacksonMapper mapper) {

        return BidderDepsAssembler.forBidder(BIDDER_NAME)
                .withConfig(criteoConfigurationProperties)
                .usersyncerCreator(UsersyncerCreator.create(externalUrl))
                .bidderCreator(config ->
                        new CriteoBidder(
                                config.getEndpoint(),
                                criteoConfigurationProperties.getGenerateSlotId()
                                        ? new UUIDIdGenerator()
                                        : new NoneIdGenerator(),
                                mapper))
                .assemble();
    }

    @Validated
    @Data
    @EqualsAndHashCode(callSuper = true)
    @NoArgsConstructor
    private static class CriteoConfigurationProperties extends BidderConfigurationProperties {

        @NotNull
        private Boolean generateSlotId;
    }
}
