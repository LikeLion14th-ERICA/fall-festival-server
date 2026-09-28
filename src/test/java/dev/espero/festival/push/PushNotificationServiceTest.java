package dev.espero.festival.push;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.TopicManagementResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class PushNotificationServiceTest {
    @Test
    void subscriptionRequiresExactlyOneSuccessfulToken() throws Exception {
        FirebaseMessaging messaging = mock(FirebaseMessaging.class);
        TopicManagementResponse response = mock(TopicManagementResponse.class);
        when(messaging.subscribeToTopic(List.of("test-token"), "test-topic")).thenReturn(response);
        var service = new FirebasePushNotificationService(messaging, "test-topic", null);
        when(response.getFailureCount()).thenReturn(1);
        assertThatThrownBy(() -> service.subscribe("test-token")).isInstanceOf(PushSubscriptionFailedException.class);
        when(response.getFailureCount()).thenReturn(0);
        assertThatThrownBy(() -> service.subscribe("test-token")).isInstanceOf(PushSubscriptionFailedException.class);
        when(response.getSuccessCount()).thenReturn(1);
        assertThatCode(() -> service.subscribe("test-token")).doesNotThrowAnyException();
        when(messaging.subscribeToTopic(any(), any())).thenThrow(mock(FirebaseMessagingException.class));
        assertThatThrownBy(() -> service.subscribe("test-token")).isInstanceOf(PushSubscriptionFailedException.class);
    }

    @Test
    void unconfiguredPushFailsSubscriptionButDoesNotBlockNoticePublishing() {
        var service = new NoOpPushNotificationService();
        assertThatThrownBy(() -> service.subscribe("test-token")).isInstanceOf(PushSubscriptionFailedException.class);
        assertThatCode(() -> service.notifyNoticeCreated("test", "test")).doesNotThrowAnyException();
    }
}
