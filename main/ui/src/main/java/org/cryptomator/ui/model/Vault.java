package org.cryptomator.ui.model;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.text.Normalizer.Form;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import org.apache.commons.lang3.StringUtils;
import org.cryptomator.crypto.Cryptor;
import org.cryptomator.crypto.SamplingDecorator;
import org.cryptomator.crypto.aes256.Aes256Cryptor;
import org.cryptomator.ui.Main;
import org.cryptomator.ui.util.MasterKeyFilter;
import org.cryptomator.ui.util.mount.CommandFailedException;
import org.cryptomator.ui.util.mount.WebDavMount;
import org.cryptomator.ui.util.mount.WebDavMounter;
import org.cryptomator.webdav.WebDavServer;
import org.cryptomator.webdav.WebDavServer.ServletLifeCycleAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

@JsonSerialize(using = VaultSerializer.class)
@JsonDeserialize(using = VaultDeserializer.class)
public class Vault implements Serializable {

	private static final long serialVersionUID = 3754487289683599469L;
	private static final Logger LOG = LoggerFactory.getLogger(Vault.class);

	public static final String VAULT_FILE_EXTENSION = ".cryptomator";

	private final Cryptor cryptor = SamplingDecorator.decorate(new Aes256Cryptor());
	private final ObjectProperty<Boolean> unlocked = new SimpleObjectProperty<Boolean>(this, "unlocked", Boolean.FALSE);
	private final Runnable shutdownTask = new ShutdownTask();
	private final Path path;
	private boolean verifyFileIntegrity;
	private String mountName;
	private ServletLifeCycleAdapter webDavServlet;
	private WebDavMount webDavMount;

	public Vault(final Path vaultDirectoryPath) {
		if (!Files.isDirectory(vaultDirectoryPath) || !vaultDirectoryPath.getFileName().toString().endsWith(VAULT_FILE_EXTENSION)) {
			throw new IllegalArgumentException("Not a valid vault directory: " + vaultDirectoryPath);
		}
		this.path = vaultDirectoryPath;

		try {
			setMountName(getName());
		} catch (IllegalArgumentException e) {
			// mount name needs to be set by the user explicitly later
		}
	}

	public boolean containsMasterKey() throws IOException {
		return MasterKeyFilter.filteredDirectory(path).iterator().hasNext();
	}

	public synchronized boolean startServer() {
		if (webDavServlet != null && webDavServlet.isRunning()) {
			return false;
		}
		webDavServlet = WebDavServer.getInstance().createServlet(path, verifyFileIntegrity, cryptor, getMountName());
		if (webDavServlet.start()) {
			Main.addShutdownTask(shutdownTask);
			return true;
		} else {
			return false;
		}
	}

	public void stopServer() {
		if (webDavServlet != null && webDavServlet.isRunning()) {
			Main.removeShutdownTask(shutdownTask);
			this.unmount();
			webDavServlet.stop();
			cryptor.swipeSensitiveData();
		}
	}

	public boolean mount() {
		if (webDavServlet == null || !webDavServlet.isRunning()) {
			return false;
		}
		try {
			webDavMount = WebDavMounter.mount(webDavServlet.getServletUri(), getMountName());
			return true;
		} catch (CommandFailedException e) {
			LOG.warn("mount failed", e);
			return false;
		}
	}

	public boolean unmount() {
		try {
			if (webDavMount != null) {
				webDavMount.unmount();
				webDavMount = null;
			}
			return true;
		} catch (CommandFailedException e) {
			LOG.warn("unmount failed", e);
			return false;
		}
	}

	/* Getter/Setter */

	public Path getPath() {
		return path;
	}

	public boolean shouldVerifyFileIntegrity() {
		return verifyFileIntegrity;
	}

	public void setVerifyFileIntegrity(boolean verifyFileIntegrity) {
		this.verifyFileIntegrity = verifyFileIntegrity;
	}

	/**
	 * @return Directory name without preceeding path components and file extension
	 */
	public String getName() {
		return StringUtils.removeEnd(path.getFileName().toString(), VAULT_FILE_EXTENSION);
	}

	public Cryptor getCryptor() {
		return cryptor;
	}

	public ObjectProperty<Boolean> unlockedProperty() {
		return unlocked;
	}

	public boolean isUnlocked() {
		return unlocked.get();
	}

	public void setUnlocked(boolean unlocked) {
		this.unlocked.set(unlocked);
	}

	public String getMountName() {
		return mountName;
	}

	/**
	 * Tries to form a similar string using the regular latin alphabet.
	 * 
	 * @param string
	 * @return a string composed of a-z, A-Z, 0-9, and _.
	 */
	public static String normalize(String string) {
		String normalized = Normalizer.normalize(string, Form.NFD);
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < normalized.length(); i++) {
			char c = normalized.charAt(i);
			if (Character.isWhitespace(c)) {
				if (builder.length() == 0 || builder.charAt(builder.length() - 1) != '_') {
					builder.append('_');
				}
			} else if (c < 127 && Character.isLetterOrDigit(c)) {
				builder.append(c);
			} else if (c < 127) {
				if (builder.length() == 0 || builder.charAt(builder.length() - 1) != '_') {
					builder.append('_');
				}
			}
		}
		return builder.toString();
	}

	/**
	 * sets the mount name while normalizing it
	 * 
	 * @param mountName
	 * @throws IllegalArgumentException if the name is empty after normalization
	 */
	public void setMountName(String mountName) throws IllegalArgumentException {
		mountName = normalize(mountName);
		if (StringUtils.isEmpty(mountName)) {
			throw new IllegalArgumentException("mount name is empty");
		}
		this.mountName = mountName;
	}

	/* hashcode/equals */

	@Override
	public int hashCode() {
		return path.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof Vault) {
			final Vault other = (Vault) obj;
			return this.path.equals(other.path);
		} else {
			return false;
		}
	}

	/* graceful shutdown */

	private class ShutdownTask implements Runnable {

		@Override
		public void run() {
			stopServer();
		}

	}

}