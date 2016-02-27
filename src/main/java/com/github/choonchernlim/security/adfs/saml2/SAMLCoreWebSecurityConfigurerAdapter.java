package com.github.choonchernlim.security.adfs.saml2;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.protocol.Protocol;
import org.apache.commons.httpclient.protocol.ProtocolSocketFactory;
import org.apache.velocity.app.VelocityEngine;
import org.opensaml.common.xml.SAMLConstants;
import org.opensaml.saml2.core.AuthnContext;
import org.opensaml.saml2.metadata.provider.HTTPMetadataProvider;
import org.opensaml.saml2.metadata.provider.MetadataProvider;
import org.opensaml.saml2.metadata.provider.MetadataProviderException;
import org.opensaml.xml.parse.StaticBasicParserPool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.MethodInvokingFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.saml.SAMLAuthenticationProvider;
import org.springframework.security.saml.SAMLDiscovery;
import org.springframework.security.saml.SAMLEntryPoint;
import org.springframework.security.saml.SAMLLogoutFilter;
import org.springframework.security.saml.SAMLLogoutProcessingFilter;
import org.springframework.security.saml.SAMLProcessingFilter;
import org.springframework.security.saml.SAMLWebSSOHoKProcessingFilter;
import org.springframework.security.saml.context.SAMLContextProviderImpl;
import org.springframework.security.saml.key.JKSKeyManager;
import org.springframework.security.saml.key.KeyManager;
import org.springframework.security.saml.log.SAMLDefaultLogger;
import org.springframework.security.saml.metadata.CachingMetadataManager;
import org.springframework.security.saml.metadata.ExtendedMetadataDelegate;
import org.springframework.security.saml.metadata.MetadataDisplayFilter;
import org.springframework.security.saml.metadata.MetadataGenerator;
import org.springframework.security.saml.metadata.MetadataGeneratorFilter;
import org.springframework.security.saml.parser.ParserPoolHolder;
import org.springframework.security.saml.processor.HTTPArtifactBinding;
import org.springframework.security.saml.processor.HTTPPAOS11Binding;
import org.springframework.security.saml.processor.HTTPPostBinding;
import org.springframework.security.saml.processor.HTTPRedirectDeflateBinding;
import org.springframework.security.saml.processor.HTTPSOAP11Binding;
import org.springframework.security.saml.processor.SAMLBinding;
import org.springframework.security.saml.processor.SAMLProcessorImpl;
import org.springframework.security.saml.trust.httpclient.TLSProtocolConfigurer;
import org.springframework.security.saml.trust.httpclient.TLSProtocolSocketFactory;
import org.springframework.security.saml.util.VelocityFactory;
import org.springframework.security.saml.websso.ArtifactResolutionProfileImpl;
import org.springframework.security.saml.websso.SingleLogoutProfile;
import org.springframework.security.saml.websso.SingleLogoutProfileImpl;
import org.springframework.security.saml.websso.WebSSOProfile;
import org.springframework.security.saml.websso.WebSSOProfileConsumer;
import org.springframework.security.saml.websso.WebSSOProfileConsumerHoKImpl;
import org.springframework.security.saml.websso.WebSSOProfileConsumerImpl;
import org.springframework.security.saml.websso.WebSSOProfileECPImpl;
import org.springframework.security.saml.websso.WebSSOProfileImpl;
import org.springframework.security.saml.websso.WebSSOProfileOptions;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.channel.ChannelProcessingFilter;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.util.Collections;
import java.util.Timer;

public abstract class SAMLCoreWebSecurityConfigurerAdapter extends WebSecurityConfigurerAdapter {

    private final SAMLConfigBean samlConfigBean;
    @Autowired
    private SAMLAuthenticationProvider samlAuthenticationProvider;

    protected SAMLCoreWebSecurityConfigurerAdapter(SAMLConfigBean samlConfigBean) {
        this.samlConfigBean = samlConfigBean;
    }

    // IDP metadata URL
    private String getMetdataUrl() {
        return String.format("https://%s/federationmetadata/2007-06/federationmetadata.xml",
                             samlConfigBean.getAdfsHostName());
    }

    // CSRF must be disabled when processing /saml/** to prevent "Expected CSRF token not found" exception.
    // See: http://stackoverflow.com/questions/26508835/spring-saml-extension-and-spring-security-csrf-protection-conflict/26560447
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.antMatcher("/saml/**")
                .httpBasic()
                .authenticationEntryPoint(samlEntryPoint())
                .and()
                .csrf().disable()
                .addFilterBefore(metadataGeneratorFilter(), ChannelProcessingFilter.class)
                .addFilterAfter(filterChainProxy(), BasicAuthenticationFilter.class);
    }

    @Override
    public void configure(final WebSecurity web) throws Exception {
        web.ignoring().antMatchers(samlConfigBean.getSuccessLogoutUrl());
    }

    // Entry point to initialize authentication
    @Bean
    public SAMLEntryPoint samlEntryPoint() {
        WebSSOProfileOptions webSSOProfileOptions = new WebSSOProfileOptions();

        // Disable element scoping when sending requests to IdP to prevent
        // "Response has invalid status code urn:oasis:names:tc:SAML:2.0:status:Responder, status message is null"
        // exception
        webSSOProfileOptions.setIncludeScoping(false);

        // Always use HTTP-Redirect instead of HTTP-Post, although both works with ADFS
        webSSOProfileOptions.setBinding(SAMLConstants.SAML2_REDIRECT_BINDING_URI);

        // Force IdP to re-authenticate user if issued token is too old to prevent
        // "Authentication statement is too old to be used with value" exception
        // See: http://stackoverflow.com/questions/30528636/saml-login-errors
        webSSOProfileOptions.setForceAuthN(true);

        // Disable SSO by forcing login page to appear, even though SSO is enabled on ADFS.
        // By default, if SSO is enable on ADFS, certain browsers will automatically log user in through
        // Windows Integrated Auth (WIA) and user agent detection, and there's no proper way to log out
        // since it will automatically log user back in.
        //
        // This ensures the app can properly log out when clicking on the log out button and
        // ensures the unified IDP login page is served regardless of the types of browser used.
        webSSOProfileOptions.setAuthnContexts(Collections.singletonList(AuthnContext.PASSWORD_AUTHN_CTX));

        SAMLEntryPoint samlEntryPoint = new SAMLEntryPoint();
        samlEntryPoint.setDefaultProfileOptions(webSSOProfileOptions);

        return samlEntryPoint;
    }

    // Filter automatically generates default SP metadata
    @Bean
    public MetadataGeneratorFilter metadataGeneratorFilter() {
        MetadataGenerator metadataGenerator = new MetadataGenerator();
        metadataGenerator.setKeyManager(keyManager());
        return new MetadataGeneratorFilter(metadataGenerator);
    }

    // HTTP client
    @Bean
    public HttpClient httpClient() {
        return new HttpClient(new MultiThreadedHttpConnectionManager());
    }

    // Filters for processing of SAML messages
    @Bean
    public FilterChainProxy filterChainProxy() throws Exception {
        //@formatter:off
        return new FilterChainProxy(ImmutableList.<SecurityFilterChain>of(
                new DefaultSecurityFilterChain(new AntPathRequestMatcher("/saml/login/**"), samlEntryPoint()),
                new DefaultSecurityFilterChain(new AntPathRequestMatcher("/saml/logout/**"), samlLogoutFilter()),
                new DefaultSecurityFilterChain(new AntPathRequestMatcher("/saml/metadata/**"), metadataDisplayFilter()),
                new DefaultSecurityFilterChain(new AntPathRequestMatcher("/saml/SSO/**"), samlProcessingFilter()),
                new DefaultSecurityFilterChain(new AntPathRequestMatcher("/saml/SSOHoK/**"), samlWebSSOHoKProcessingFilter()),
                new DefaultSecurityFilterChain(new AntPathRequestMatcher("/saml/SingleLogout/**"), samlLogoutProcessingFilter()),
                new DefaultSecurityFilterChain(new AntPathRequestMatcher("/saml/discovery/**"), samlIDPDiscovery())
        ));
        //@formatter:on
    }

    // Handler deciding where to redirect user after successful login
    @Bean
    public SavedRequestAwareAuthenticationSuccessHandler successRedirectHandler() {
        SavedRequestAwareAuthenticationSuccessHandler successRedirectHandler = new SavedRequestAwareAuthenticationSuccessHandler();
        successRedirectHandler.setDefaultTargetUrl(samlConfigBean.getSuccessLoginDefaultUrl());
        return successRedirectHandler;
    }

    // Handler deciding where to redirect user after failed login
    @Bean
    public SimpleUrlAuthenticationFailureHandler failureRedirectHandler() {
        SimpleUrlAuthenticationFailureHandler failureHandler = new SimpleUrlAuthenticationFailureHandler();

        // The precondition on `setDefaultFailureUrl(..)` will cause an exception if the value is null.
        // So, only set this value if it is not null
        if (!samlConfigBean.getFailedLoginDefaultUrl().isEmpty()) {
            failureHandler.setDefaultFailureUrl(samlConfigBean.getFailedLoginDefaultUrl());
        }

        return failureHandler;
    }

    // Handler for successful logout
    @Bean
    public SimpleUrlLogoutSuccessHandler successLogoutHandler() {
        SimpleUrlLogoutSuccessHandler successLogoutHandler = new SimpleUrlLogoutSuccessHandler();
        successLogoutHandler.setDefaultTargetUrl(samlConfigBean.getSuccessLogoutUrl());
        return successLogoutHandler;
    }

    // Authentication manager
    @Bean
    @Override
    public AuthenticationManager authenticationManagerBean() throws Exception {
        return super.authenticationManagerBean();
    }

    // Register authentication manager for SAML provider
    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth.authenticationProvider(samlAuthenticationProvider);
    }

    // Logger for SAML messages and events
    @Bean
    public SAMLDefaultLogger samlLogger() {
        return new SAMLDefaultLogger();
    }

    // Central storage of cryptographic keys
    @Bean
    public KeyManager keyManager() {
        return new JKSKeyManager(samlConfigBean.getKeyStoreResource(),
                                 samlConfigBean.getKeystorePassword(),
                                 ImmutableMap.of(samlConfigBean.getKeystoreAlias(),
                                                 samlConfigBean.getKeystorePassword()),
                                 samlConfigBean.getKeystoreAlias());
    }

    // IDP Discovery service
    @Bean
    public SAMLDiscovery samlIDPDiscovery() {
        return new SAMLDiscovery();
    }

    // The filter is waiting for connections on URL suffixed with filterSuffix and presents SP metadata there
    @Bean
    public MetadataDisplayFilter metadataDisplayFilter() {
        return new MetadataDisplayFilter();
    }

    // Configure HTTP Client to accept certificates from the keystore instead of JDK keystore  for HTTPS verification
    @Bean
    public TLSProtocolConfigurer tlsProtocolConfigurer() {
        return new TLSProtocolConfigurer();
    }

    // Configure TLSProtocolConfigurer
    @Bean
    public ProtocolSocketFactory protocolSocketFactory() {
        return new TLSProtocolSocketFactory(keyManager(), null, "default");
    }

    // Configure TLSProtocolConfigurer
    @Bean
    public Protocol protocol() {
        return new Protocol("https", protocolSocketFactory(), 443);
    }

    // Configure TLSProtocolConfigurer
    @Bean
    public MethodInvokingFactoryBean socketFactoryInitialization() {
        MethodInvokingFactoryBean methodInvokingFactoryBean = new MethodInvokingFactoryBean();
        methodInvokingFactoryBean.setTargetClass(Protocol.class);
        methodInvokingFactoryBean.setTargetMethod("registerProtocol");
        methodInvokingFactoryBean.setArguments(new Object[]{"https", protocol()});
        return methodInvokingFactoryBean;
    }

    // IDP Metadata configuration - paths to metadata of IDPs in circle of trust is here
    @Bean
    public CachingMetadataManager metadata() throws MetadataProviderException {
        HTTPMetadataProvider httpMetadataProvider = new HTTPMetadataProvider(new Timer(true),
                                                                             httpClient(),
                                                                             getMetdataUrl());
        httpMetadataProvider.setParserPool(parserPool());

        ExtendedMetadataDelegate extendedMetadataDelegate = new ExtendedMetadataDelegate(httpMetadataProvider);
        // Disable metadata trust check to prevent "Signature trust establishment failed for metadata entry" exception
        extendedMetadataDelegate.setMetadataTrustCheck(false);

        return new CachingMetadataManager(ImmutableList.<MetadataProvider>of(extendedMetadataDelegate));
    }

    // SAML Authentication Provider responsible for validating of received SAML messages
    @Bean
    public SAMLAuthenticationProvider samlAuthenticationProvider() {
        SAMLAuthenticationProvider samlAuthenticationProvider = new SAMLAuthenticationProvider();
        if (samlConfigBean.getUserDetailsService() != null) {
            samlAuthenticationProvider.setUserDetails(samlConfigBean.getUserDetailsService());
        }
        return samlAuthenticationProvider;
    }

    // Provider of default SAML Context
    @Bean
    public SAMLContextProviderImpl contextProvider() {
        return new SAMLContextProviderImpl();
    }

    // Processing filter for WebSSO profile messages
    @Bean
    public SAMLProcessingFilter samlProcessingFilter() throws Exception {
        SAMLProcessingFilter samlWebSSOProcessingFilter = new SAMLProcessingFilter();
        samlWebSSOProcessingFilter.setAuthenticationManager(authenticationManager());
        samlWebSSOProcessingFilter.setAuthenticationSuccessHandler(successRedirectHandler());
        samlWebSSOProcessingFilter.setAuthenticationFailureHandler(failureRedirectHandler());
        return samlWebSSOProcessingFilter;
    }

    // Processing filter for WebSSO Holder-of-Key profile
    @Bean
    public SAMLWebSSOHoKProcessingFilter samlWebSSOHoKProcessingFilter() throws Exception {
        SAMLWebSSOHoKProcessingFilter samlWebSSOHoKProcessingFilter = new SAMLWebSSOHoKProcessingFilter();
        samlWebSSOHoKProcessingFilter.setAuthenticationSuccessHandler(successRedirectHandler());
        samlWebSSOHoKProcessingFilter.setAuthenticationManager(authenticationManager());
        samlWebSSOHoKProcessingFilter.setAuthenticationFailureHandler(failureRedirectHandler());
        return samlWebSSOHoKProcessingFilter;
    }

    // Logout handler terminating local session
    @Bean
    public SecurityContextLogoutHandler logoutHandler() {
        SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();
        logoutHandler.setInvalidateHttpSession(true);
        logoutHandler.setClearAuthentication(true);
        return logoutHandler;
    }

    // Override default logout processing filter with the one processing SAML messages
    @Bean
    public SAMLLogoutFilter samlLogoutFilter() {
        return new SAMLLogoutFilter(successLogoutHandler(),
                                    new LogoutHandler[]{logoutHandler()},
                                    new LogoutHandler[]{logoutHandler()});
    }

    // Filter processing incoming logout messages
    // First argument determines URL user will be redirected to after successful global logout
    @Bean
    public SAMLLogoutProcessingFilter samlLogoutProcessingFilter() {
        return new SAMLLogoutProcessingFilter(successLogoutHandler(), logoutHandler());
    }

    // Class loading incoming SAML messages from httpRequest stream
    @Bean
    public SAMLProcessorImpl processor() {
        return new SAMLProcessorImpl(ImmutableList.<SAMLBinding>of(redirectDeflateBinding(),
                                                                   postBinding(),
                                                                   artifactBinding(),
                                                                   soapBinding(),
                                                                   paosBinding()));
    }

    // SAML 2.0 WebSSO Assertion Consumer
    @Bean
    public WebSSOProfileConsumer webSSOprofileConsumer() {
        return new WebSSOProfileConsumerImpl();
    }

    // SAML 2.0 Holder-of-Key WebSSO Assertion Consumer
    @Bean
    public WebSSOProfileConsumerHoKImpl hokWebSSOprofileConsumer() {
        return new WebSSOProfileConsumerHoKImpl();
    }

    // SAML 2.0 Web SSO profile
    @Bean
    public WebSSOProfile webSSOprofile() {
        return new WebSSOProfileImpl();
    }

    // SAML 2.0 Holder-of-Key Web SSO profile
    @Bean
    public WebSSOProfileConsumerHoKImpl hokWebSSOProfile() {
        return new WebSSOProfileConsumerHoKImpl();
    }

    // SAML 2.0 ECP profile
    @Bean
    public WebSSOProfileECPImpl ecpprofile() {
        return new WebSSOProfileECPImpl();
    }

    // SAML 2.0 Logout profile
    @Bean
    public SingleLogoutProfile logoutprofile() {
        return new SingleLogoutProfileImpl();
    }

    // Bindings, encoders and decoders used for creating and parsing messages
    @Bean
    public HTTPPostBinding postBinding() {
        return new HTTPPostBinding(parserPool(), velocityEngine());
    }

    @Bean
    public HTTPRedirectDeflateBinding redirectDeflateBinding() {
        return new HTTPRedirectDeflateBinding(parserPool());
    }

    @Bean
    public HTTPArtifactBinding artifactBinding() {
        ArtifactResolutionProfileImpl artifactResolutionProfile = new ArtifactResolutionProfileImpl(httpClient());
        artifactResolutionProfile.setProcessor(new SAMLProcessorImpl(soapBinding()));
        return new HTTPArtifactBinding(parserPool(), velocityEngine(), artifactResolutionProfile);
    }

    @Bean
    public HTTPSOAP11Binding soapBinding() {
        return new HTTPSOAP11Binding(parserPool());
    }

    @Bean
    public HTTPPAOS11Binding paosBinding() {
        return new HTTPPAOS11Binding(parserPool());
    }

    // Initialization of the velocity engine
    @Bean
    public VelocityEngine velocityEngine() {
        return VelocityFactory.getEngine();
    }

    // XML parser pool needed for OpenSAML parsing
    @Bean(initMethod = "initialize")
    public StaticBasicParserPool parserPool() {
        return new StaticBasicParserPool();
    }

    @Bean
    public ParserPoolHolder parserPoolHolder() {
        return new ParserPoolHolder();
    }
}