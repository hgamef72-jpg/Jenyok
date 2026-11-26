public final class ClassLoadTest {
    public static void main(String[] args) throws Exception {
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        for (String name : args) {
            Class.forName(name, false, loader);
            System.out.println("Verified JVM class: " + name);
        }
    }
}
