package com.esherkness.musictransitiondiagnostics.coreprobe;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/** premain is used only by Gradle run and the isolated probe smoke tests. */
public final class CoreProbeAgent
{
	static final String AREA_FADE_TEST_MODE = "area-incoming-fade-test";
	static final int MAX_INCOMING_FADE = 300;
	static final String EXPECTED_SHA256 =
		"25f42961c400bd9dfff1554402441c0ba6d1cffd011163cb9b0b4c42ae194f85";

	private CoreProbeAgent() { }

	public static void premain(String agentArguments, Instrumentation instrumentation)
	{
		try
		{
			CoreProbeLog.initialize(Path.of(System.getProperty("musicCoreProbe.log",
				"build/music-core-probe.log")));
			Runtime.getRuntime().addShutdownHook(new Thread(CoreProbeLog::close, "music-core-probe-close"));
			final Integer incomingFade;
			try
			{
				incomingFade = parseIncomingFade(agentArguments);
			}
			catch (IllegalArgumentException rejected)
			{
				CoreProbeLog.disable("invalid-agent-mode-or-incoming-fade");
				return;
			}
			ClassLoader loader = ClassLoader.getSystemClassLoader();
			List<URL> resources = Collections.list(loader.getResources("rj.class"));
			if (resources.size() != 1 || !resources.get(0).getProtocol().equals("jar"))
			{
				CoreProbeLog.disable("missing-or-ambiguous-injected-client");
				return;
			}
			JarURLConnection resource = (JarURLConnection) resources.get(0).openConnection();
			resource.setUseCaches(false);
			Path jar = Path.of(resource.getJarFileURL().toURI()).toRealPath();
			if (!jar.getFileName().toString().equals("injected-client-1.12.39.jar")
				|| !sha256(Files.readAllBytes(jar)).equals(EXPECTED_SHA256))
			{
				CoreProbeLog.disable("unsupported-injected-client-version-or-hash");
				return;
			}
			Map<String, byte[]> originals = new LinkedHashMap<>();
			try (JarFile archive = new JarFile(jar.toFile()))
			{
				for (String name : ProbeTransformer.TARGETS.keySet())
				{
					// Check resources too: another classpath entry must not shadow a target.
					List<URL> found = Collections.list(loader.getResources(name + ".class"));
					if (found.size() != 1 || !found.get(0).toString().equals(
						"jar:" + jar.toUri().toURL() + "!/" + name + ".class"))
					{
						CoreProbeLog.disable("missing-or-shadowed-class-" + name);
						return;
					}
					try (InputStream in = archive.getInputStream(archive.getJarEntry(name + ".class")))
					{
						originals.put(name, in.readAllBytes());
					}
				}
			}
			for (Class<?> loaded : instrumentation.getAllLoadedClasses())
			{
				if (ProbeTransformer.TARGETS.containsKey(loaded.getName()))
				{
					CoreProbeLog.disable("target-already-loaded");
					return;
				}
			}
			ProbeTransformer transformer = new ProbeTransformer(jar, loader, originals, incomingFade != null);
			instrumentation.addTransformer(transformer, false);
			CoreProbeLog.arm(incomingFade);
		}
		catch (Throwable ignored)
		{
			// A rejected signature/agent failure must never abort the normal launcher.
			try { CoreProbeLog.disable("preflight-failed"); }
			catch (Throwable alsoIgnored) { }
		}
	}

	/** Null means the ordinary observation-only launcher. */
	static Integer parseIncomingFade(String agentArguments)
	{
		if (agentArguments == null || agentArguments.isEmpty())
		{
			return null;
		}
		String prefix = AREA_FADE_TEST_MODE + ":";
		if (!agentArguments.startsWith(prefix))
		{
			throw new IllegalArgumentException("unsupported agent mode");
		}
		String raw = agentArguments.substring(prefix.length());
		if (!raw.matches("[1-9][0-9]{0,2}"))
		{
			throw new IllegalArgumentException("invalid incoming fade syntax");
		}
		int value = Integer.parseInt(raw);
		if (value > MAX_INCOMING_FADE)
		{
			throw new IllegalArgumentException("incoming fade exceeds maximum");
		}
		return value;
	}

	static String sha256(byte[] bytes) throws Exception
	{
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
		StringBuilder hex = new StringBuilder();
		for (byte b : digest)
		{
			hex.append(String.format("%02x", b & 255));
		}
		return hex.toString();
	}
}
