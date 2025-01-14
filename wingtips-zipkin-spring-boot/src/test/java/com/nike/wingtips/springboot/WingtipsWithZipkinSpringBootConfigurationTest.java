package com.nike.wingtips.springboot;

import com.nike.wingtips.Tracer;
import com.nike.wingtips.lifecyclelistener.SpanLifecycleListener;
import com.nike.wingtips.springboot.componenttest.componentscanonly.ComponentTestMainWithComponentScanOnly;
import com.nike.wingtips.springboot.componenttest.manualimportandcomponentscan.ComponentTestMainWithBothManualImportAndComponentScan;
import com.nike.wingtips.springboot.componenttest.manualimportonly.ComponentTestMainManualImportOnly;
import com.nike.wingtips.zipkin.WingtipsToZipkinLifecycleListener;
import com.nike.wingtips.zipkin.util.ZipkinSpanSenderDefaultHttpImpl;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.internal.util.reflection.Whitebox;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Tests the functionality of {@link WingtipsWithZipkinSpringBootConfiguration}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class WingtipsWithZipkinSpringBootConfigurationTest {

    @Before
    public void beforeMethod() {
        clearTracerSpanLifecycleListeners();
    }

    @After
    public void afterMethod() {
        clearTracerSpanLifecycleListeners();
    }

    private void clearTracerSpanLifecycleListeners() {
        Tracer.getInstance().removeAllSpanLifecycleListeners();
    }

    private WingtipsZipkinProperties generateProps(boolean disabled,
                                                   String baseUrl,
                                                   String serviceName,
                                                   String localComponentNamespace) {
        WingtipsZipkinProperties props = new WingtipsZipkinProperties();
        props.setZipkinDisabled(String.valueOf(disabled));
        props.setBaseUrl(baseUrl);
        props.setServiceName(serviceName);
        props.setLocalComponentNamespace(localComponentNamespace);
        return props;
    }

    @Test
    public void constructor_registers_WingtipsToZipkinLifecycleListener() {
        // given
        String baseUrl = "http://localhost:4242/" + UUID.randomUUID().toString();
        String serviceName = UUID.randomUUID().toString();
        String localComponentNamespace = UUID.randomUUID().toString();
        WingtipsZipkinProperties props = generateProps(false, baseUrl, serviceName, localComponentNamespace);

        // when
        new WingtipsWithZipkinSpringBootConfiguration(props);

        // then
        List<SpanLifecycleListener> listeners = Tracer.getInstance().getSpanLifecycleListeners();
        assertThat(listeners).hasSize(1);
        assertThat(listeners.get(0)).isInstanceOf(WingtipsToZipkinLifecycleListener.class);
        WingtipsToZipkinLifecycleListener listener = (WingtipsToZipkinLifecycleListener) listeners.get(0);
        Object zipkinSpanSender = Whitebox.getInternalState(listener, "zipkinSpanSender");
        assertThat(zipkinSpanSender).isInstanceOf(ZipkinSpanSenderDefaultHttpImpl.class);
        assertThat(Whitebox.getInternalState(zipkinSpanSender, "postZipkinSpansUrl").toString())
            .isEqualTo(baseUrl + "/api/v1/spans");
        assertThat(Whitebox.getInternalState(listener, "serviceName")).isEqualTo(serviceName);
        assertThat(Whitebox.getInternalState(listener, "localComponentNamespace"))
            .isEqualTo(localComponentNamespace);
    }

    @Test
    public void constructor_does_not_register_WingtipsToZipkinLifecycleListener_when_props_shouldApplyWingtipsToZipkinLifecycleListener_returns_false() {
        // given
        WingtipsZipkinProperties props = mock(WingtipsZipkinProperties.class);
        doReturn(false).when(props).shouldApplyWingtipsToZipkinLifecycleListener();

        // when
        new WingtipsWithZipkinSpringBootConfiguration(props);

        // then
        assertThat(Tracer.getInstance().getSpanLifecycleListeners()).isEmpty();
        verify(props).shouldApplyWingtipsToZipkinLifecycleListener();
        verifyNoMoreInteractions(props);
    }

    private enum ComponentTestSetup {
        MANUAL_IMPORT_ONLY(ComponentTestMainManualImportOnly.class, false),
        COMPONENT_SCAN_ONLY(ComponentTestMainWithComponentScanOnly.class, true),
        BOTH_MANUAL_AND_COMPONENT_SCAN(ComponentTestMainWithBothManualImportAndComponentScan.class, true);

        final boolean expectComponentScannedObjects;
        final Class<?> mainClass;

        ComponentTestSetup(Class<?> mainClass, boolean expectComponentScannedObjects) {
            this.mainClass = mainClass;
            this.expectComponentScannedObjects = expectComponentScannedObjects;
        }
    }

    // This component test verifies that a Spring Boot application successfully utilizes WingtipsSpringBootConfiguration
    //      and WingtipsSpringBootProperties when it is component scanned, imported manually, or both. Specifically
    //      we should not get multiple bean definition errors even when WingtipsSpringBootConfiguration is *both*
    //      component scanned *and* imported manually.
    @DataProvider(value = {
        "MANUAL_IMPORT_ONLY",
        "COMPONENT_SCAN_ONLY",
        "BOTH_MANUAL_AND_COMPONENT_SCAN"
    })
    @Test
    public void component_test(ComponentTestSetup componentTestSetup) {
        // given
        int serverPort = findFreePort();
        Class<?> mainClass = componentTestSetup.mainClass;

        ConfigurableApplicationContext serverAppContext = SpringApplication.run(mainClass, "--server.port=" + serverPort);

        try {
            // when
            WingtipsSpringBootConfiguration baseConfig = serverAppContext.getBean(WingtipsSpringBootConfiguration.class);
            WingtipsWithZipkinSpringBootConfiguration zipkinConfig =
                serverAppContext.getBean(WingtipsWithZipkinSpringBootConfiguration.class);
            WingtipsSpringBootProperties baseProps = serverAppContext.getBean(WingtipsSpringBootProperties.class);
            WingtipsZipkinProperties zipkinProps = serverAppContext.getBean(WingtipsZipkinProperties.class);

            String[] someComponentScannedClassBeanNames =
                serverAppContext.getBeanNamesForType(SomeComponentScannedClass.class);

            // then
            // Sanity check that we component scanned (or not) as appropriate.
            if (componentTestSetup.expectComponentScannedObjects) {
                assertThat(someComponentScannedClassBeanNames).isNotEmpty();
            }
            else {
                assertThat(someComponentScannedClassBeanNames).isEmpty();
            }

            // WingtipsSpringBootConfiguration, WingtipsWithZipkinSpringBootConfiguration,
            //      WingtipsSpringBootProperties, and WingtipsZipkinProperties should be available as beans, and
            //      the base config should use the same props we received.
            assertThat(baseConfig).isNotNull();
            assertThat(baseProps).isNotNull();
            assertThat(baseConfig.wingtipsProperties).isSameAs(baseProps);

            assertThat(zipkinConfig).isNotNull();
            assertThat(zipkinProps).isNotNull();
        }
        finally {
            SpringApplication.exit(serverAppContext);
        }
    }

    private static int findFreePort() {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Component
    private static class SomeComponentScannedClass {
    }

}