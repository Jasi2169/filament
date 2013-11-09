package org.frustra.filament;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.frustra.filament.hooking.CustomClassNode;
import org.frustra.filament.injection.Injection;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

public abstract class FilamentClassLoader extends URLClassLoader {
	public FilamentStorage filament;
	private ClassLoader parent;

	public FilamentClassLoader(boolean debug) throws IOException {
		this(debug, FilamentClassLoader.class.getClassLoader());
	}

	public FilamentClassLoader(boolean debug, ClassLoader parent) throws IOException {
		super(new URL[0]);
		this.filament = new FilamentStorage(this, debug);
		this.parent = parent;
	}
	
	public void loadJar(File jarFile) throws IOException {
		JarFile jar = new JarFile(jarFile);
		try {
			loadJar(jar, jarFile.toURI().toURL());
		} finally {
			jar.close();
		}
	}

	public void loadJar(JarFile jar, URL url) throws IOException {
		Enumeration<JarEntry> entries = jar.entries();
		while (entries.hasMoreElements()) {
			JarEntry entry = entries.nextElement();
			if (entry != null && entry.getName().endsWith(".class")) {
				CustomClassNode node = CustomClassNode.loadFromStream(jar.getInputStream(entry));
				String name = node.name.replaceAll("/", ".");
				filament.classes.put(name, node);
			}
		}
		if (url != null) addURL(url);
	}

	public void loadPackage(String packageName) throws IOException, ClassNotFoundException, URISyntaxException {
		String[] classes = listPackage(packageName);
		for (String name : classes) {
			InputStream stream = getResourceAsStream(name.replace('.', '/') + ".class");
			CustomClassNode node = CustomClassNode.loadFromStream(stream);
			filament.classes.put(name, node);
		}
	}

	public void loadClasses(Class<?>[] classes) throws IOException, ClassNotFoundException {
		for (Class<?> cls : classes) {
			InputStream stream = getResourceAsStream(cls.getName().replace('.', '/') + ".class");
			CustomClassNode node = CustomClassNode.loadFromStream(stream);
			filament.classes.put(cls.getName(), node);
		}
	}

	public String[] listPackage(String packageName) throws IOException, ClassNotFoundException, URISyntaxException {
		ArrayList<String> classes = new ArrayList<String>();
		URL codeRoot = FilamentClassLoader.class.getProtectionDomain().getCodeSource().getLocation();
		if (codeRoot == null) throw new ClassNotFoundException("Couldn't determine code root!");
		File root = new File(codeRoot.toURI().getPath());
		if (root.isDirectory()) {
			URL packageURL = getResource(packageName.replace('.', '/'));
			if (packageURL == null) throw new ClassNotFoundException("Couldn't load package location: " + packageName);
			File packageFolder = new File(packageURL.getFile());
			for (File f : packageFolder.listFiles()) {
				String name = f.getName();
				if (f.isFile() && !name.startsWith(".") && name.endsWith(".class")) {
					FileInputStream stream = new FileInputStream(f);
					ClassReader reader = new ClassReader(stream);
					stream.close();
					classes.add(reader.getClassName().replace('/', '.'));
				}
			}
		} else if (root.getAbsolutePath().endsWith(".jar")) {
			JarFile file = new JarFile(root);
			Enumeration<? extends JarEntry> entries = file.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				if (entry == null || entry.isDirectory()) continue;
				String entryPath = entry.getName();
				String fileName = entryPath.substring(entryPath.lastIndexOf("/") + 1);

				if (fileName.endsWith(".class")) {
					ClassReader reader = new ClassReader(file.getInputStream(entry));
					String className = reader.getClassName().replace('/', '.');
					String packageName2 = className.substring(0, className.lastIndexOf('.'));
					if (packageName.equals(packageName2)) classes.add(className);
				}
			}
			file.close();
		} else {
			System.out.println("Unknown source type: " + root.getAbsolutePath());
		}
		return classes.toArray(new String[0]);
	}

	protected Class<?> getPrimitiveType(String name) throws ClassNotFoundException {
		if (name.equals("byte") || name.equals("B")) return byte.class;
		if (name.equals("short") || name.equals("S")) return short.class;
		if (name.equals("int") || name.equals("I")) return int.class;
		if (name.equals("long") || name.equals("J")) return long.class;
		if (name.equals("char") || name.equals("C")) return char.class;
		if (name.equals("float") || name.equals("F")) return float.class;
		if (name.equals("double") || name.equals("D")) return double.class;
		if (name.equals("boolean") || name.equals("Z")) return boolean.class;
		if (name.equals("void") || name.equals("V")) return void.class;
		// new ClassNotFoundException(name).printStackTrace();
		throw new ClassNotFoundException(name);
	}

	HashMap<String, Class<?>> loaded = new HashMap<String, Class<?>>();

	public Class<?> loadClass(String name) throws ClassNotFoundException {
		Class<?> cls = loaded.get(name);
		if (cls == null) {
			cls = defineClass(name);
			if (cls != null) loaded.put(name, cls);
		}
		return cls;
	}

	protected abstract Class<?> defineClass(String name, byte[] buf);

	private Class<?> defineClass(String name) throws ClassNotFoundException {
		if (name == null) return null;
		try {
			byte[] buf = getClassBytes(name);
			if (buf != null) {
				return defineClass(name, buf);
			} else {
				try {
					return super.loadClass(name);
				} catch (Exception e1) {
					try {
						return parent.loadClass(name);
					} catch (Exception e2) {
						return getPrimitiveType(name);
					}
				}
			}
		} catch (Exception e) {
			throw new ClassNotFoundException(name, e);
		}
	}

	public byte[] getClassBytes(String name) {
		CustomClassNode node = filament.classes.get(name);

		if (node != null) {
			Injection.injectClass(node);

			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			node.accept(writer);

			return writer.toByteArray();
		} else return null;
	}

	public InputStream getResourceAsStream(String name) {
		if (name.endsWith(".class")) {
			byte[] buf = getClassBytes(name.substring(0, name.length() - 6).replace('/', '.'));
			if (buf != null) return new ByteArrayInputStream(buf);
		}
		InputStream stream = null;
		try {
			stream = super.getResourceAsStream(name);
		} catch (Throwable e) {}
		if (stream != null) return stream;
		try {
			stream = parent.getResourceAsStream(name);
		} catch (Throwable e) {}
		if (stream != null) return stream;
		try {
			stream = FilamentClassLoader.class.getResourceAsStream("/" + name);
		} catch (Throwable e) {}
		if (stream != null) return stream;
		return getResourceAsStreamAlt(name);
	}
	
	public InputStream getResourceAsStreamAlt(String name) {
		return null;
	}

	public URL findResource(String name) {
		byte[] buf = null;
		if (name.endsWith(".class")) buf = getClassBytes(name.substring(0, name.length() - 6).replace('/', '.'));
		if (buf == null) {
			URL url = null;
			try {
				url = super.findResource(name);
			} catch (Throwable e) {}
			if (url != null) return url;
			try {
				url = parent.getResource(name);
			} catch (Throwable e) {}
			if (url != null) return url;
			try {
				url = FilamentClassLoader.class.getResource("/" + name);
			} catch (Throwable e) {}
			return url;
		}
		return findResourceFromBuffer(name, buf);
	}
	
	public URL findResourceFromBuffer(String name, byte[] buf) {
		final InputStream stream = new ByteArrayInputStream(buf);
		URLStreamHandler handler = new URLStreamHandler() {
			protected URLConnection openConnection(URL url) throws IOException {
				return new URLConnection(url) {
					public void connect() throws IOException {}

					public InputStream getInputStream() {
						return stream;
					}
				};
			}
		};
		try {
			return new URL(new URL("http://www.frustra.org/"), "", handler);
		} catch (MalformedURLException e) {
			e.printStackTrace();
			return null;
		}
	}
}
