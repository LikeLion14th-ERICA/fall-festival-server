package dev.espero.festival.context;

import dev.espero.festival.domain.PublishedFestivalContext;
import dev.espero.festival.persistence.FestivalContextStore;
import dev.espero.festival.web.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@Profile("db")
public class FestivalContextService {

    private final FestivalContextStore store;
    private final FestivalProperties properties;

    public FestivalContextService(FestivalContextStore store, FestivalProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    public PublishedFestivalContext currentPublished() {
        try {
            return store.findPublishedByFestivalId(properties.configuredFestivalId())
                .orElseThrow(() -> new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "FESTIVAL_CONTEXT_UNAVAILABLE",
                    "현재 축제 정보를 제공할 수 없습니다.",
                    true
                ));
        } catch (DataAccessException exception) {
            throw new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "SERVICE_UNAVAILABLE",
                "일시적으로 정보를 불러올 수 없습니다.",
                true
            );
        }
    }
}
