package com.jobpilot.telegram.review;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TelegramJobDeliveryRepository extends JpaRepository<TelegramJobDelivery, Long> {
    @Query("select d.jobId from TelegramJobDelivery d "
            + "where d.chatId = :chatId and d.deliveryType = :type and d.jobId in :jobIds")
    List<Long> findDeliveredJobIds(@Param("chatId") long chatId,
                                   @Param("type") DeliveryType type,
                                   @Param("jobIds") Collection<Long> jobIds);

    boolean existsByChatIdAndJobIdAndDeliveryType(long chatId, long jobId, DeliveryType deliveryType);
}
