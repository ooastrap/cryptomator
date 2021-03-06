/*******************************************************************************
 * Copyright (c) 2016 Sebastian Stenzel and others.
 * This file is licensed under the terms of the MIT license.
 * See the LICENSE.txt file for more info.
 *
 * Contributors:
 *     Sebastian Stenzel - initial API and implementation
 *******************************************************************************/
package org.cryptomator.ui;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Named;
import javax.inject.Singleton;

import org.cryptomator.common.CommonsModule;
import org.cryptomator.cryptolib.CryptoLibModule;
import org.cryptomator.frontend.webdav.WebDavServer;
import org.cryptomator.jni.JniModule;
import org.cryptomator.keychain.KeychainModule;
import org.cryptomator.ui.settings.Settings;
import org.cryptomator.ui.settings.SettingsProvider;
import org.cryptomator.ui.util.DeferredCloser;
import org.fxmisc.easybind.EasyBind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dagger.Module;
import dagger.Provides;
import javafx.application.Application;
import javafx.beans.binding.Binding;
import javafx.stage.Stage;

@Module(includes = {CommonsModule.class, KeychainModule.class, JniModule.class, CryptoLibModule.class})
class CryptomatorModule {

	private static final Logger LOG = LoggerFactory.getLogger(CryptomatorModule.class);
	private final Application application;
	private final Stage mainWindow;

	public CryptomatorModule(Application application, Stage mainWindow) {
		this.application = application;
		this.mainWindow = mainWindow;
	}

	@Provides
	@Singleton
	Application provideApplication() {
		return application;
	}

	@Provides
	@Singleton
	@Named("mainWindow")
	Stage provideMainWindow() {
		return mainWindow;
	}

	@Provides
	@Singleton
	DeferredCloser provideDeferredCloser() {
		DeferredCloser closer = new DeferredCloser();
		Cryptomator.addShutdownTask(() -> {
			try {
				closer.close();
			} catch (Exception e) {
				LOG.error("Error during shutdown.", e);
			}
		});
		return closer;
	}

	@Provides
	@Singleton
	Settings provideSettings(SettingsProvider settingsProvider) {
		return settingsProvider.get();
	}

	@Provides
	@Singleton
	ExecutorService provideExecutorService(DeferredCloser closer) {
		return closer.closeLater(Executors.newCachedThreadPool(), ExecutorService::shutdown).get().orElseThrow(IllegalStateException::new);
	}

	@Provides
	@Singleton
	Binding<InetSocketAddress> provideServerSocketAddressBinding(Settings settings) {
		return EasyBind.combine(settings.useIpv6(), settings.port(), (useIpv6, port) -> {
			String host = useIpv6 ? "::1" : "localhost";
			return InetSocketAddress.createUnresolved(host, port.intValue());
		});
	}

	@Provides
	@Singleton
	WebDavServer provideWebDavServer(Binding<InetSocketAddress> serverSocketAddressBinding) {
		WebDavServer server = WebDavServer.create();
		// no need to unsubscribe eventually, because server is a singleton
		EasyBind.subscribe(serverSocketAddressBinding, server::bind);
		return server;
	}

}
