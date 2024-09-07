package com.example.demo.streams

import com.illenko.avro.Purchase
import com.illenko.avro.PurchasePattern
import com.illenko.avro.RewardAccumulator
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.kstream.Produced
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class PurchaseStream {
    @Bean
    fun kStream(
        @Value("\${spring.kafka.streams.properties.schema.registry.url}") schemaRegistryUrl: String,
        builder: StreamsBuilder,
    ): KStream<String, Purchase> {
        val serdeConfig = mapOf("schema.registry.url" to schemaRegistryUrl)

        val purchaseKStream =
            builder
                .stream(
                    "purchase",
                    Consumed.with(
                        Serdes.String(),
                        SpecificAvroSerde<Purchase>().apply {
                            configure(serdeConfig, false)
                        },
                    ),
                ).mapValues { p -> p.toMasked() }

        val patternKStream =
            purchaseKStream.mapValues { p -> p.toPattern() }

        patternKStream.to(
            "patterns",
            Produced.with(
                Serdes.String(),
                SpecificAvroSerde<PurchasePattern>().apply {
                    configure(serdeConfig, false)
                },
            ),
        )

        val rewardsKStream =
            purchaseKStream.mapValues { p -> p.toReward() }

        rewardsKStream.to(
            "rewards",
            Produced.with(
                Serdes.String(),
                SpecificAvroSerde<RewardAccumulator>().apply {
                    configure(serdeConfig, false)
                },
            ),
        )

        return purchaseKStream
    }

    fun Purchase.toMasked(): Purchase =
        this.apply {
            this.creditCardNumber = "**** **** **** " + this.creditCardNumber.takeLast(4)
        }

    fun Purchase.toPattern(): PurchasePattern =
        PurchasePattern
            .newBuilder()
            .setZipCode(this.zipCode)
            .setItem(this.itemPurchased)
            .setDate(this.purchaseDate)
            .setAmount(this.price * this.quantity)
            .build()

    fun Purchase.toReward(): RewardAccumulator =
        RewardAccumulator
            .newBuilder()
            .setCustomerId("${this.firstName},${this.lastName}")
            .setPurchaseTotal(this.price * this.quantity)
            .setTotalRewardPoints(0)
            .setCurrentRewardPoints(0)
            .setDaysFromLastPurchase(0)
            .build()
}
