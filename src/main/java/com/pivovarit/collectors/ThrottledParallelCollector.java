package com.pivovarit.collectors;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.supplyAsync;

/**
 * @author Grzegorz Piwowarek
 */
class ThrottledParallelCollector<T, R1, R2 extends Collection<R1>>
  implements Collector<T, List<CompletableFuture<R1>>, CompletableFuture<R2>> {

    private final Executor executor;
    private final Supplier<R2> collectionSupplier;
    private final Function<T, R1> operation;
    private final Semaphore permits;

    ThrottledParallelCollector(Function<T, R1> operation, Supplier<R2> collection, Executor executor, int parallelism) {
        this.executor = executor;
        this.collectionSupplier = collection;
        this.operation = operation;
        this.permits = new Semaphore(parallelism);
    }

    @Override
    public Supplier<List<CompletableFuture<R1>>> supplier() {
        return ArrayList::new;
    }

    @Override
    public BiConsumer<List<CompletableFuture<R1>>, T> accumulator() {
        return (acc, e) -> {
            try {
                permits.acquire();
            } catch (InterruptedException e1) {
                Thread.currentThread().interrupt();
            }

            try {
                acc.add(supplyAsync(() -> {
                    try {
                        return operation.apply(e);
                    } finally {
                        permits.release();
                    }
                }, executor));
            } catch (RejectedExecutionException ex) {
                permits.release();
                throw ex;
            }
        };
    }

    @Override
    public BinaryOperator<List<CompletableFuture<R1>>> combiner() {
        return (left, right) -> {
            left.addAll(right);
            return left;
        };
    }

    @Override
    public Function<List<CompletableFuture<R1>>, CompletableFuture<R2>> finisher() {
        return futures -> futures.stream()
          .reduce(completedFuture(collectionSupplier.get()),
            accumulatingResults(),
            mergingPartialResults());
    }

    @Override
    public Set<Characteristics> characteristics() {
        return EnumSet.of(Characteristics.UNORDERED);
    }

    private static <T1, R1 extends Collection<T1>> BinaryOperator<CompletableFuture<R1>> mergingPartialResults() {
        return (f1, f2) -> f1.thenCombine(f2, (left, right) -> {
            left.addAll(right);
            return left;
        });
    }

    private static <T1, R1 extends Collection<T1>> BiFunction<CompletableFuture<R1>, CompletableFuture<T1>, CompletableFuture<R1>> accumulatingResults() {
        return (list, object) -> list.thenCombine(object, (left, right) -> {
            left.add(right);
            return left;
        });
    }
}