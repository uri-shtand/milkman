package milkman.plugin.test;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import milkman.domain.Environment;
import milkman.domain.RequestContainer;
import milkman.domain.ResponseContainer;
import milkman.plugin.test.domain.*;
import milkman.plugin.test.domain.TestAspect.TestDetails;
import milkman.plugin.test.domain.TestResultAspect.TestResultEvent;
import milkman.ui.plugin.PluginRequestExecutor;
import milkman.ui.plugin.Templater;
import milkman.utils.AsyncResponseControl.AsyncControl;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.ReplayProcessor;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static milkman.plugin.test.domain.TestResultAspect.TestResultState.*;

@Slf4j
@RequiredArgsConstructor
public class TestRunner {

	private final PluginRequestExecutor executor;

	public ResponseContainer executeRequest(TestContainer request,
											Templater templater,
											AsyncControl asyncControl) {

		var testAspect = request.getAspect(TestAspect.class)
				.orElseThrow(() -> new IllegalArgumentException("Missing test aspect"));

		var testEnvironment = getOverrideEnvironment(testAspect);

		asyncControl.triggerReqeuestStarted();


		var replay = ReplayProcessor.<TestResultEvent>create();
		Flux<TestResultEvent> resultFlux = Flux.<TestResultEvent>create(sink -> {
			var subscription = Flux.fromIterable(testAspect.getRequests())
					.index()
					.flatMap(tuple -> Mono.justOrEmpty(executor.getDetails(tuple.getT2().getId()).map(r -> Tuples.of(tuple.getT1(), tuple.getT2(), r))))
					.filter(t -> {
						var skip = t.getT2().isSkip();
						if (skip){
							sink.next(new TestResultEvent(t.getT1().toString(), t.getT3().getName(), SKIPPED, Map.of()));
						}
						return !skip;
					})
					.doOnNext(tuple -> sink.next(new TestResultEvent(tuple.getT1().toString(), tuple.getT3().getName(), STARTED, Map.of())))
					.flatMap(tuple -> execute(tuple, testEnvironment, sink))
					.flatMap(testSuccess -> {
						if (!testSuccess && testAspect.isStopOnFirstFailure()) {
							return Mono.error(new RuntimeException("Test failed"));
						}
						return Mono.empty();
					})
//				.switchIfEmpty(Mono.defer(() -> {
//					log.error("Request could not be found");
//					return Mono.just(new TestResultEvent("", "", TestResultAspect.TestResultState.EXCEPTION));
//				}))
					.doFinally(s -> {
						asyncControl.triggerRequestSucceeded();
						sink.complete();
					})
					.subscribeOn(Schedulers.elastic())
					.publish().connect();
			asyncControl.onCancellationRequested.add(subscription::dispose);
		}).doOnComplete(() -> {
			if (testAspect.isPropagateResultEnvironment()){

			}
		})
		.subscribeWith(replay);

		var container = new TestResultContainer();
		container.getAspects().add(new TestResultAspect(replay));
		container.getAspects().add(new TestResultEnvAspect(testEnvironment));
		return container;
	}


	private Environment getOverrideEnvironment(TestAspect testAspect){
		var environment = new Environment("override");
		environment.setActive(true);
		environment.setOrAdd("__TEST__", "true");
		testAspect.getEnvironmentOverride().forEach(entry -> environment.setOrAdd(entry.getName(), entry.getValue()));
		return environment;
	}

	private Mono<Boolean> execute(Tuple3<Long, TestDetails, RequestContainer> request, Environment overrideEnv, FluxSink<TestResultEvent> replay) {
		var testDetails = request.getT2();
		var retryDelay = Duration.ofMillis(testDetails.getWaitBetweenRetriesInMs());
		return Mono.defer(() -> Mono.just(executor.executeRequest(request.getT3(), Optional.of(overrideEnv))))
				.retryWhen(Retry.fixedDelay(testDetails.getRetries(), retryDelay)
//						.filter(t -> !testDetails.isIgnore()) // might be wanted behavior: retry on error but ignore result, so we dont use this filter here
						.scheduler(Schedulers.elastic()))
				.flatMap(res -> Mono.fromFuture(res.getStatusInformations()))
				.doOnNext(si -> replay.next(new TestResultEvent(request.getT1().toString(), request.getT3().getName(), SUCCEEDED, si)))
				.then(Mono.just(true))
				.onErrorResume(err -> {
					var state = testDetails.isIgnore() ? IGNORED : FAILED;
					replay.next(new TestResultEvent(request.getT1().toString(), request.getT3().getName(), state, Map.of("exception", err.toString())));
					return Mono.just(testDetails.isIgnore()); //pretend success, if ignore is true
				});
	}
}
