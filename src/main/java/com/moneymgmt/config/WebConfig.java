package com.moneymgmt.config;

import java.time.Duration;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    public static final Locale THAI = Locale.forLanguageTag("th");

    /** Language choice is remembered in a cookie for a year; English by default. */
    @Bean
    LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver("lang") {
            @Override
            public void setLocale(jakarta.servlet.http.HttpServletRequest request,
                                  jakarta.servlet.http.HttpServletResponse response, Locale locale) {
                super.setLocale(request, response, supported(locale));
            }

            @Override
            public Locale resolveLocale(jakarta.servlet.http.HttpServletRequest request) {
                return supported(super.resolveLocale(request));
            }
        };
        resolver.setDefaultLocale(Locale.ENGLISH);
        resolver.setCookieMaxAge(Duration.ofDays(365));
        return resolver;
    }

    /** UI text lives in messages_en.properties and messages_th.properties (no base file, so declared here). */
    @Bean
    org.springframework.context.MessageSource messageSource() {
        var source = new org.springframework.context.support.ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setDefaultLocale(Locale.ENGLISH);
        return source;
    }

    /** Only English and Thai exist; anything else falls back to English. */
    static Locale supported(Locale locale) {
        return locale != null && "th".equals(locale.getLanguage()) ? THAI : Locale.ENGLISH;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");
        interceptor.setIgnoreInvalidLocale(true);
        registry.addInterceptor(interceptor);
    }
}
