package com.nageoffer.shortlink.project.toolkit;

public class Base62Util {
    private static final char[] BASE62 =  "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            .toCharArray();
    private Base62Util() {
    }//私有构造器，禁止 new 对象，工具类只使用静态方法

    public static String encode(long num){
        if (num == 0) {
            return "0";
        }

        StringBuilder sb = new StringBuilder();

        while (num > 0){
            int remainder = (int) (num % 62);
            sb.append(BASE62[remainder]);
            num /= 62;
        }
        return sb.reverse().toString();
    }
}
