package org.dreamexposure.discal.cam.spring

import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import org.dreamexposure.discal.core.`object`.BotSettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.web.reactive.config.EnableWebFlux
import org.springframework.web.reactive.config.WebFluxConfigurer

@Configuration
@EnableWebFlux
class WebFluxConfig: WebFluxConfigurer {

    @Bean(name = ["redisDatasource"])
    fun redisConnectionFactory(): LettuceConnectionFactory {
        val rsc = RedisStandaloneConfiguration()
        rsc.hostName = BotSettings.REDIS_HOSTNAME.get()
        rsc.port = BotSettings.REDIS_PORT.get().toInt()
        if (BotSettings.REDIS_USE_PASSWORD.get().equals("true", true))
            rsc.password = RedisPassword.of(BotSettings.REDIS_PASSWORD.get())

        return LettuceConnectionFactory(rsc)
    }

    @Bean(name = ["mysqlDatasource"])
    fun mysqlConnectionFactory(): ConnectionFactory {
        return ConnectionFactories.get(ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "pool")
                .option(ConnectionFactoryOptions.PROTOCOL, "mysql")
                .option(ConnectionFactoryOptions.HOST, BotSettings.SQL_HOST.get())
                .option(ConnectionFactoryOptions.PORT, BotSettings.SQL_PORT.get().toInt())
                .option(ConnectionFactoryOptions.USER, BotSettings.SQL_USER.get())
                .option(ConnectionFactoryOptions.PASSWORD, BotSettings.SQL_PASS.get())
                .option(ConnectionFactoryOptions.DATABASE, BotSettings.SQL_DB.get())
                .build())
    }
}