package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;

import java.io.Closeable;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * The plugins on <em>one project's</em> resolved classpath, loaded from that project's own jars.
 *
 * <p>This is what makes the inversion's rule — <em>a bot gets its answers from its own version</em> — true
 * of the palette and the value vocabulary rather than only of the generated source. A project pins an SDK;
 * Maven resolves that jar; this loads the {@link StudioPlugin} out of <em>that</em> jar and not out of the
 * one Studio itself was compiled against.
 *
 * <h2>Nothing here reflects on a plugin's own classes</h2>
 *
 * <p>{@link ServiceLoader#load(Class, ClassLoader)} hands back instances <em>typed as</em>
 * {@link StudioPlugin}, so every later call — {@code id()}, {@code catalog(pin)}, {@code valueTypes()} — is
 * an ordinary javac-checked one. Reflection happens once, inside {@code ServiceLoader}, to invoke a no-arg
 * constructor. Studio never names a plugin's implementation class, and the rule that keeps that safe is
 * already true of this repository: <b>every type crossing the boundary is a contract type or a JDK type.</b>
 *
 * <h2>The delegation split is the load-bearing part, and it is not the JDK default</h2>
 *
 * <p>A plain {@link URLClassLoader} is parent-first for everything, which would resolve the plugin class
 * from Studio's <em>own</em> compile dependency — the bundled SDK — and defeat the whole point: the pinned
 * SDK is not the bundled SDK. So the split is inverted, in one direction only:
 *
 * <ul>
 *   <li><b>parent-first</b> for {@code com.botmaker.plugin.api.**} and the platform namespaces. A contract
 *       class must be the <em>same</em> {@link Class} object on both sides, or handing a
 *       {@code PaletteCatalog} back across the boundary throws {@link ClassCastException} against a type
 *       whose name is identical to the one it was expected to be — the least diagnosable failure available.
 *   <li><b>child-first</b> for everything else, notably {@code com.botmaker.sdk.**}, falling back to the
 *       parent when the child has no such class.
 * </ul>
 *
 * <p>While Studio still declares a compile dependency on the SDK, two SDK class-spaces are live at once —
 * Studio's own and this loader's. <b>They must never exchange an SDK type.</b> They do not today: every
 * Studio consumer of a catalog entry reaches it through {@code simpleName()} / {@code qualifiedName()} /
 * {@code isFacade()}, and nothing compares a {@code Class<?>} across the two. Keeping it that way is a
 * condition of this class working, not a tidiness preference.
 */
final class PluginLoader implements Closeable {

    /**
     * Namespaces the parent answers first. The contract is here for the identity reason above; the platform
     * three are here because a child copy of {@code java.**} is either impossible or a disaster, and because
     * a plugin bundling its own JavaFX would otherwise get a second toolkit in a process that has one.
     */
    private static final List<String> PARENT_FIRST =
            List.of("com.botmaker.plugin.api.", "java.", "javax.", "javafx.", "jdk.", "sun.");

    private final URLClassLoader loader;
    private final List<StudioPlugin> plugins;

    private PluginLoader(URLClassLoader loader, List<StudioPlugin> plugins) {
        this.loader = loader;
        this.plugins = plugins;
    }

    /**
     * Loads every plugin declared on {@code classpath}, or {@code null} when there is nothing to load or
     * nothing loadable.
     *
     * <p>Null rather than an empty loader on purpose: the caller's fallback is the bundled plugin set, and a
     * project whose classpath resolved to nothing must get the bundled menus rather than none. Every failure
     * here is one of those — an unresolvable pin, a jar with no services file, a plugin whose constructor
     * throws — and each is logged and answered the same way.
     */
    static PluginLoader open(List<String> classpath) {
        if (classpath == null || classpath.isEmpty()) return null;
        URL[] urls = urlsOf(classpath);
        if (urls.length == 0) return null;

        URLClassLoader loader = new Inverted(urls, PluginLoader.class.getClassLoader());
        List<StudioPlugin> found = new ArrayList<>();
        try {
            // Iterated with an explicit loop rather than stream().toList(): a ServiceConfigurationError is
            // thrown lazily, per provider, so one plugin that will not instantiate must not cost the rest.
            for (StudioPlugin plugin : ServiceLoader.load(StudioPlugin.class, loader)) {
                found.add(plugin);
            }
        } catch (ServiceConfigurationError | RuntimeException e) {
            System.err.println("Warning: could not load plugins from the project classpath: " + e);
        }
        if (found.isEmpty()) {
            close(loader);
            return null;
        }
        return new PluginLoader(loader, List.copyOf(found));
    }

    List<StudioPlugin> plugins() {
        return plugins;
    }

    /**
     * Releases the jars. Required rather than housekeeping: an open {@link URLClassLoader} holds every jar it
     * read, and on Windows a held jar cannot be replaced — so a project left unclosed makes the next
     * <em>Manage Libraries</em> resolve fail on a file lock.
     */
    @Override
    public void close() {
        close(loader);
    }

    private static void close(URLClassLoader loader) {
        try {
            loader.close();
        } catch (Exception e) {
            System.err.println("Warning: could not close the plugin classloader: " + e);
        }
    }

    private static URL[] urlsOf(List<String> classpath) {
        List<URL> urls = new ArrayList<>(classpath.size());
        for (String entry : classpath) {
            if (entry == null || entry.isBlank()) continue;
            try {
                urls.add(new File(entry).toURI().toURL());
            } catch (MalformedURLException e) {
                // A classpath entry that is not a path is not a reason to lose the rest of the classpath.
                System.err.println("Warning: skipping classpath entry " + entry + ": " + e.getMessage());
            }
        }
        return urls.toArray(URL[]::new);
    }

    /** The inverted-delegation loader described in the class javadoc. */
    private static final class Inverted extends URLClassLoader {

        Inverted(URL[] urls, ClassLoader parent) {
            super("botmaker-plugins", urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) loaded = findAnywhere(name);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        private Class<?> findAnywhere(String name) throws ClassNotFoundException {
            if (parentFirst(name)) return super.loadClass(name, false);
            try {
                return findClass(name);
            } catch (ClassNotFoundException notInTheProject) {
                // Everything a plugin needs that its own jars do not carry — the JDK's wider surface, and
                // for now the libraries Studio and the SDK happen to share.
                return super.loadClass(name, false);
            }
        }

        private static boolean parentFirst(String name) {
            for (String prefix : PARENT_FIRST) {
                if (name.startsWith(prefix)) return true;
            }
            return false;
        }
    }
}
