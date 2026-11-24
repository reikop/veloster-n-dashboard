/* SPDX-License-Identifier: Apache-2.0. Local API17 compatibility helper. */
package javax.jmdns.impl.compat;
public final class Objects {
    private Objects(){}
    public static boolean equals(Object left,Object right){return left==right||(left!=null&&left.equals(right));}
}
