package com.ttl.tabletennis.market;

import com.ttl.tabletennis.domain.OddsSnapshot;
import java.util.List;

/** Contract for authorized sportsbook/reference data providers. */
public interface MarketSourceAdapter {
    String sourceCode();
    String displayName();
    boolean authorized();
    List<OddsSnapshot> latest(String eventIdentity);
}
