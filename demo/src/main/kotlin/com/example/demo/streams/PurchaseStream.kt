package com.example.demo.streams

import com.example.demo.securitydb.SecurityDbService
import com.illenko.avro.Purchase
import com.illenko.avro.PurchasePattern
import com.illenko.avro.RewardAccumulator
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.kstream.Branched
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.kstream.Produced
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class PurchaseStream(
    private val purchaseSerde: SpecificAvroSerde<Purchase>,
    private val purchasePatternSerde: SpecificAvroSerde<PurchasePattern>,
    private val rewardAccumulatorSerde: SpecificAvroSerde<RewardAccumulator>,
    private val securityDbService: SecurityDbService,
) {
    @Bean
    fun kStream(builder: StreamsBuilder): KStream<String, Purchase> {
        val purchaseKStream = createPurchaseStream(builder)
        createPatternStream(purchaseKStream)
        createRewardsStream(purchaseKStream)
        splitStreamByDepartment(purchaseKStream)
        filterAndSaveToSecurityDb(purchaseKStream)
        filterAndMaskPurchases(purchaseKStream)

        return purchaseKStream
    }

    private fun createPurchaseStream(builder: StreamsBuilder): KStream<String, Purchase> =
        builder
            .stream(
                "purchase",
                Consumed.with(
                    Serdes.String(),
                    purchaseSerde,
                ),
            ).mapValues { p -> p.toMasked() }

    private fun createPatternStream(purchaseKStream: KStream<String, Purchase>): KStream<String, PurchasePattern> {
        val patternKStream = purchaseKStream.mapValues { p -> p.toPattern() }
        patternKStream.to(
            "patterns",
            Produced.with(
                Serdes.String(),
                purchasePatternSerde,
            ),
        )
        return patternKStream
    }

    private fun createRewardsStream(purchaseKStream: KStream<String, Purchase>): KStream<String, RewardAccumulator> {
        val rewardsKStream = purchaseKStream.mapValues { p -> p.toReward() }
        rewardsKStream.to(
            "rewards",
            Produced.with(
                Serdes.String(),
                rewardAccumulatorSerde,
            ),
        )
        return rewardsKStream
    }

    private fun splitStreamByDepartment(purchaseKStream: KStream<String, Purchase>) {
        purchaseKStream
            .split()
            .branch(
                { _, purchase -> "coffee" == purchase.department },
                Branched.withConsumer { ks ->
                    ks.to(
                        "coffee",
                        Produced.with(
                            Serdes.String(),
                            purchaseSerde,
                        ),
                    )
                },
            ).branch(
                { _, purchase -> "electronics" == purchase.department },
                Branched.withConsumer { ks ->
                    ks.to(
                        "electronics",
                        Produced.with(
                            Serdes.String(),
                            purchaseSerde,
                        ),
                    )
                },
            )
    }

    private fun filterAndSaveToSecurityDb(purchaseKStream: KStream<String, Purchase>) {
        purchaseKStream
            .filter { _, v -> v.employeeId == "E100" }
            .foreach { _, v -> securityDbService.save(v) }
    }

    private fun filterAndMaskPurchases(purchaseKStream: KStream<String, Purchase>) {
        purchaseKStream
            .filter { _, v -> v.price > 20.0 }
            .selectKey { _, v -> v.purchaseDate.toEpochMilli() }
            .to(
                "purchase-masked",
                Produced.with(
                    Serdes.Long(),
                    purchaseSerde,
                ),
            )
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
