package org.meistermeier.neo4j.resilience4j.ogm;

import static org.assertj.core.api.Assertions.*;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.vavr.control.Try;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.meistermeier.neo4j.resilience4j.ogm.person.Person;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.exceptions.SessionExpiredException;
import org.neo4j.driver.exceptions.TransientException;
import org.neo4j.ogm.config.Configuration;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.transaction.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This test class shows an approach how to combine Neo4j-OGM and Resilience4j to simulate the behaviour
 * of the Neo4j Java driver transaction functions in cases a retryable exception occurs.
 *
 * references in the comments below
 * [1] https://github.com/neo4j/neo4j-java-driver/blob/58aa5481ab2b5b8caefcf1001acc51e5a87ab0d6/driver/src/main/java/org/neo4j/driver/internal/retry/ExponentialBackoffRetryLogic.java#L145
 * [2] https://github.com/neo4j/neo4j-java-driver/blob/58aa5481ab2b5b8caefcf1001acc51e5a87ab0d6/driver/src/main/java/org/neo4j/driver/internal/retry/ExponentialBackoffRetryLogic.java#L326
 * [3] https://github.com/neo4j/neo4j-java-driver/blob/80440bc7a153b531b4829c0a46d6ffea6b5f21ff/driver/src/main/java/org/neo4j/driver/internal/InternalSession.java#L147
 *
 * @author Gerrit Meier
 */
class SessionWithRetryTest {

	private static final Logger LOG = LoggerFactory.getLogger(SessionWithRetryTest.class);
	private static final String NEO4J_USER = "neo4j";
	private static final String NEO4J_PASSWORD = "secret";
	private static final String NEO4J_URI = "neo4j://localhost:7687";
	private SessionFactory sessionFactory;
	private RetryConfig retryConfig;

	@BeforeEach
	void setupConnectionAndRetryUnitOfWork() {
		Configuration configuration = new Configuration.Builder()
			.credentials(NEO4J_USER, NEO4J_PASSWORD)
			.uri(NEO4J_URI)
			.build();

		sessionFactory = new SessionFactory(configuration, "org.meistermeier.neo4j.resilience4j.ogm.person");

		retryConfig = RetryConfig
			.custom()
			.retryExceptions( // retry on the same exceptions the driver does [1]
				SessionExpiredException.class, ServiceUnavailableException.class)
			.retryOnException((exception) -> { // use the same strategy for TransientException as in the driver [2]
				if (exception instanceof TransientException) {
					String code = ((TransientException) exception).code();
					return !"Neo.TransientError.Transaction.Terminated".equals(code) &&
						!"Neo.TransientError.Transaction.LockClientStopped".equals(code);
				}
				return false;
			})
			.maxAttempts(3) // choose the retry attempts
			.build();
	}

	@AfterEach
	void closeConnection() {
		sessionFactory.close();
	}

	private Callable<Collection<Person>> createRetryableCallback(Session session) {
		return Retry.decorateCallable(Retry.of("retryPool", retryConfig),
			() -> {
				// apply the logic from [3] one abstraction layer above the driver
				Transaction transaction = session.beginTransaction();
				Collection<Person> people = session.loadAll(Person.class);
				transaction.commit();
				return people;
			});
	}

	/**
	 * This retry will behave just like the driver. If all attempts fail it will re-throw the original exception
	 * that has to be handled by the application side code.
	 */
	@Test
	void doRetryableWork() {
		Session session = sessionFactory.openSession();

		Callable<Collection<Person>> retryableCall = createRetryableCallback(session);

		try {
			Collection<Person> people = retryableCall.call();
			assertThat(people).isNotNull();
		} catch (Exception e) {
			LOG.error("Could not recover.", e);
		}
	}

	/**
	 * This example uses vavr's Try type to enable the user to have a recover/fallback scenario defined in a more
	 * expressive way than just using a try/catch construct.
	 */
	@Test
	void doRetryableWorkAndHandleFailure() {
		Session session = sessionFactory.openSession();

		Callable<Collection<Person>> retryableCall = createRetryableCallback(session);

		Collection<Person> people = Try.ofCallable(retryableCall)
			.onFailure((throwable) -> LOG.error("Could not execute query.", throwable))
			.recover(Exception.class, new ArrayList<>()) // here one could define more concrete Throwables to react on
			.get();

		assertThat(people).isNotNull();

	}

}
