package dev.espero.festival.web;

import dev.espero.festival.persistence.ArtistHypedBatchStore;
import dev.espero.festival.persistence.ArtistHypedStore;
import dev.espero.festival.ratelimit.HypedWriteAdmission;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class ArtistHypedBatchService {
    private final ArtistHypedBatchStore batches;
    private final ArtistHypedStore counts;

    public ArtistHypedBatchService(ArtistHypedBatchStore batches, ArtistHypedStore counts) {
        this.batches = batches;
        this.counts = counts;
    }

    @Transactional
    public Applied apply(Command command, HttpServletRequest request) {
        if (!batches.claim(command.festivalId(), command.batchId(), command.artistId(), command.delta(),
            command.countPrefix(), command.now())) {
            var receipt = batches.required(command.festivalId(), command.batchId());
            if (!receipt.artistId().equals(command.artistId()) || receipt.delta() != command.delta()) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "같은 요청 식별자로 다른 내용을 보낼 수 없습니다.", false);
            }
            return new Applied(receipt.hypedCount(), receipt.countPrefix());
        }
        if (!counts.isCurrentArtist(command.revisionId(), command.artistId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.", false);
        }
        if (!command.participationEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "HYPED_CLOSED", "지금은 기대돼요 참여 시간이 아닙니다.", false);
        }
        HypedWriteAdmission.reserveAdditionalClicks(request, command.delta());
        long count = counts.increment(command.festivalId(), command.countPrefix() + command.artistId(),
            command.delta(), command.now());
        batches.complete(command.festivalId(), command.batchId(), count);
        return new Applied(count, command.countPrefix());
    }

    public record Command(UUID festivalId, UUID revisionId, UUID batchId, String artistId, int delta,
        String countPrefix, Instant now, boolean participationEnabled) {}
    public record Applied(long count, String countPrefix) {}
}
