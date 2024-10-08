package com.example.demo.streams

import com.example.demo.extractor.PurchaseTimestampExtractor
import com.example.demo.joiner.PurchaseJoiner
import com.example.demo.partitioner.RewardsStreamPartitioner
import com.example.demo.securitydb.SecurityDbService
import com.example.demo.supplier.PurchaseRewardProcessorSupplier
import com.example.demo.transformer.PurchaseRewardTransformer
import com.illenko.avro.CorrelatedPurchase
import com.illenko.avro.Purchase
import com.illenko.avro.PurchasePattern
import com.illenko.avro.RewardAccumulator
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.kstream.Branched
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.JoinWindows
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.kstream.Named
import org.apache.kafka.streams.kstream.Produced
import org.apache.kafka.streams.kstream.Repartitioned
import org.apache.kafka.streams.kstream.StreamJoined
import org.apache.kafka.streams.state.Stores
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
class PurchaseStream(
    private val purchaseSerde: SpecificAvroSerde<Purchase>,
    private val purchasePatternSerde: SpecificAvroSerde<PurchasePattern>,
    private val rewardAccumulatorSerde: SpecificAvroSerde<RewardAccumulator>,
    private val correlatedPurchaseSerde: SpecificAvroSerde<CorrelatedPurchase>,
    private val securityDbService: SecurityDbService,
    private val rewardsStreamPartitioner: RewardsStreamPartitioner,
    private val purchaseTimestampExtractor: PurchaseTimestampExtractor,
) {
    @Bean
    fun topology(builder: StreamsBuilder): Topology {
        val purchaseKStream = createPurchaseStream(builder)
        createPatternStream(purchaseKStream)
        createRewardsStream(builder, purchaseKStream)
        splitStreamByDepartment(purchaseKStream)
        filterAndSaveToSecurityDb(purchaseKStream)
        filterAndMaskPurchases(purchaseKStream)

        return builder.build()
    }

    private fun createPurchaseStream(builder: StreamsBuilder): KStream<String, Purchase> =
        builder
            .stream(
                "purchase",
                Consumed
                    .with(
                        Serdes.String(),
                        purchaseSerde,
                    ).withTimestampExtractor(purchaseTimestampExtractor),
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

    private fun createRewardsStream(
        builder: StreamsBuilder,
        purchaseKStream: KStream<String, Purchase>,
    ): KStream<String, RewardAccumulator> {
        addStateStore(builder)
        val purchasesByCustomerStream = repartitionStream(purchaseKStream)
        val statefulRewardAccumulator = processValues(purchasesByCustomerStream)
        statefulRewardAccumulator.to("rewards", Produced.with(Serdes.String(), rewardAccumulatorSerde))
        return statefulRewardAccumulator
    }

    private fun addStateStore(builder: StreamsBuilder) {
        val storeSupplier = Stores.inMemoryKeyValueStore("rewardsPointsStore")
        val storeBuilder = Stores.keyValueStoreBuilder(storeSupplier, Serdes.String(), Serdes.Integer())
        builder.addStateStore(storeBuilder)
    }

    private fun repartitionStream(purchaseKStream: KStream<String, Purchase>): KStream<String, Purchase> =
        purchaseKStream.repartition(
            Repartitioned
                .with(Serdes.String(), purchaseSerde)
                .withName("customer_transactions")
                .withStreamPartitioner(rewardsStreamPartitioner),
        )

    private fun processValues(purchasesByCustomerStream: KStream<String, Purchase>): KStream<String, RewardAccumulator> =
        purchasesByCustomerStream.processValues(
            PurchaseRewardProcessorSupplier(PurchaseRewardTransformer()),
            Named.`as`("rewardsPointsProcessor"),
            "rewardsPointsStore",
        )

    private fun splitStreamByDepartment(purchaseKStream: KStream<String, Purchase>) {
        val branches =
            purchaseKStream
                .selectKey { _, purchase -> purchase.customerId }
                .split(Named.`as`("department-"))
                .branch(
                    { _, purchase -> "coffee" == purchase.department },
                    Branched.withFunction({ it }, "coffee"),
                ).branch(
                    { _, purchase -> "electronics" == purchase.department },
                    Branched.withFunction({ it }, "electronics"),
                ).noDefaultBranch()

        val coffeeStream = branches["department-coffee"]!!
        val electronicsStream = branches["department-electronics"]!!

        val joiner = PurchaseJoiner()
        val twentyMinuteWindow = JoinWindows.ofTimeDifferenceWithNoGrace(Duration.ofMillis(20 * 60 * 1000))

        coffeeStream
            .join(
                electronicsStream,
                joiner,
                twentyMinuteWindow,
                StreamJoined.with(
                    Serdes.String(),
                    purchaseSerde,
                    purchaseSerde,
                ),
            ).to(
                "coffee-and-electronics",
                Produced.with(
                    Serdes.String(),
                    correlatedPurchaseSerde,
                ),
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
}
