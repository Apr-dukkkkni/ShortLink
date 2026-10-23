/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
