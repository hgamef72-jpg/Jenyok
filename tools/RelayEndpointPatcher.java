import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class RelayEndpointPatcher {
  private static final String APP_JAR = "app/saturn-pc-remote.jar";
  private static final String APPS_CLASS = "com/saturn/remote/AppsKt.class";
  private static final String OLD_URL = "wss://saturn-pc-remote-relay.onrender.com/connect";

  private static byte[] patchClass(byte[] bytes, String newUrl, Counter counter) {
    ClassNode cn = new ClassNode();
    new ClassReader(bytes).accept(cn, 0);
    boolean changed = false;

    for (FieldNode field : cn.fields) {
      if (OLD_URL.equals(field.value)) {
        field.value = newUrl;
        counter.urlConstants++;
        changed = true;
      }
    }

    for (MethodNode method : cn.methods) {
      if (method.name.equals("isLegacyRelayAddress") &&
          method.desc.equals("(Ljava/lang/String;)Z")) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) method.localVariables.clear();
        LabelNode matched = new LabelNode();
        String[] markers = {
          "YOUR-WORKER",
          "YOUR-RENDER-SERVICE",
          "workers.dev",
          "saturn-pc-remote-relay.onrender.com"
        };
        for (String marker : markers) {
          method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
          method.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC,
              "java/util/Locale", "ROOT", "Ljava/util/Locale;"));
          method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
              "java/lang/String", "toLowerCase", "(Ljava/util/Locale;)Ljava/lang/String;", false));
          method.instructions.add(new LdcInsnNode(marker.toLowerCase(Locale.ROOT)));
          method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
              "java/lang/String", "contains", "(Ljava/lang/CharSequence;)Z", false));
          method.instructions.add(new JumpInsnNode(Opcodes.IFNE, matched));
        }
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.instructions.add(matched);
        method.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 2;
        method.maxLocals = 1;
        counter.legacyMethod = true;
        changed = true;
      }

      for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
        if (insn instanceof LdcInsnNode ldc && OLD_URL.equals(ldc.cst)) {
          ldc.cst = newUrl;
          counter.urlConstants++;
          changed = true;
        }
      }
    }

    if (!changed) return bytes;
    ClassWriter writer = new ClassWriter(0);
    cn.accept(writer);
    return writer.toByteArray();
  }

  private static final class Counter {
    int urlConstants;
    boolean legacyMethod;
    boolean appsClassFound;
  }

  private static byte[] patchJar(byte[] input, String newUrl, Counter counter) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(input));
         ZipOutputStream zout = new ZipOutputStream(output)) {
      ZipEntry entry;
      while ((entry = zin.getNextEntry()) != null) {
        String name = entry.getName();
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.startsWith("META-INF/") &&
            (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"))) {
          continue;
        }
        byte[] data = zin.readAllBytes();
        if (name.endsWith(".class")) {
          if (name.equals(APPS_CLASS)) counter.appsClassFound = true;
          data = patchClass(data, newUrl, counter);
        } else if (name.endsWith(".properties") || name.endsWith(".cfg") || name.endsWith(".ini")) {
          String text = new String(data, StandardCharsets.UTF_8);
          if (text.contains(OLD_URL)) {
            data = text.replace(OLD_URL, newUrl).getBytes(StandardCharsets.UTF_8);
            counter.urlConstants++;
          }
        }
        ZipEntry next = new ZipEntry(name);
        next.setTime(entry.getTime());
        zout.putNextEntry(next);
        zout.write(data);
        zout.closeEntry();
      }
    }
    if (!counter.appsClassFound) throw new IllegalStateException("AppsKt.class not found in embedded JAR");
    if (!counter.legacyMethod) throw new IllegalStateException("AppsKt.isLegacyRelayAddress(String) not found");
    if (counter.urlConstants < 1) throw new IllegalStateException("Old relay endpoint constant was not found");
    return output.toByteArray();
  }

  private static void patchPackage(Path input, Path output, String newUrl) throws Exception {
    Counter counter = new Counter();
    boolean found = false;
    try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(input));
         ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(output))) {
      ZipEntry entry;
      while ((entry = zin.getNextEntry()) != null) {
        String name = entry.getName();
        byte[] data = zin.readAllBytes();
        if (name.equals(APP_JAR)) {
          data = patchJar(data, newUrl, counter);
          found = true;
        }
        ZipEntry next = new ZipEntry(name);
        next.setTime(entry.getTime());
        zout.putNextEntry(next);
        zout.write(data);
        zout.closeEntry();
      }
    }
    if (!found) throw new IllegalStateException("Missing embedded application JAR: " + APP_JAR);
    try (ZipFile check = new ZipFile(output.toFile())) {
      ZipEntry nested = check.getEntry(APP_JAR);
      if (nested == null) throw new IllegalStateException("Patched package lost embedded JAR");
      try (ZipInputStream jar = new ZipInputStream(check.getInputStream(nested))) {
        ZipEntry entry;
        boolean appsFound = false;
        while ((entry = jar.getNextEntry()) != null) {
          if (entry.getName().equals(APPS_CLASS)) appsFound = true;
          while (jar.read(new byte[8192]) >= 0) { }
        }
        if (!appsFound) throw new IllegalStateException("Patched AppsKt class missing");
      }
    }
    System.out.println("Patched relay endpoint in " + input + " -> " + output +
        "; constants=" + counter.urlConstants + "; legacy-fallback=enabled");
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException(
        "usage: RelayEndpointPatcher input-package.zip output-package.zip wss://host/connect");
    String newUrl = args[2];
    if (!newUrl.startsWith("wss://") || !newUrl.endsWith("/connect"))
      throw new IllegalArgumentException("Relay URL must use wss:// and end with /connect");
    patchPackage(Path.of(args[0]), Path.of(args[1]), newUrl);
  }
}
