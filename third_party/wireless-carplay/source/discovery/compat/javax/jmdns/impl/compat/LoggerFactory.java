/* SPDX-License-Identifier: Apache-2.0. Local API17 logging adapter, not SLF4J. */
package javax.jmdns.impl.compat;
public final class LoggerFactory {
    private LoggerFactory(){}
    public static Logger getLogger(Class<?> type){return new Logger(type.getName());}
}
