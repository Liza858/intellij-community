// "Unwrap 'else' branch (changes semantics)" "true"

class T {
    void f(boolean b) {
        try {
            if (b)
                System.out.println("When true");
            throw new RuntimeException("Otherwise");
        } finally {
            System.out.println("Finally");
        }
    }
}