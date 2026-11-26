package org.catalogueoflife.garrulus.finder.remote;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;

/**
 * Tells a service that cannot be reached from a request that failed on its way. Only a failure to connect (refused,
 * unknown host, no route, connect timeout) says the service is down; a read timeout or a connection that breaks
 * after the request was sent may well be caused by that one request (a huge document), so it is an ordinary failure.
 */
public final class ConnectFailures {

  private ConnectFailures() {}

  /** True if the exception or one of its causes is a failure to connect. */
  public static boolean isConnectFailure(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof ConnectException || t instanceof UnknownHostException || t instanceof NoRouteToHostException
          || t instanceof HttpConnectTimeoutException) {
        return true;
      }
      if (t.getCause() == t) break;
    }
    return false;
  }

  /** True for the statuses of a gateway or service that is down or overloaded: 502, 503 and 504. */
  public static boolean isUnavailableStatus(int status) {
    return status == 502 || status == 503 || status == 504;
  }
}
