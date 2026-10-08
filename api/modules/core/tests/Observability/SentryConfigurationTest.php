<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Observability;

use ApiPlatform\Validator\Exception\ValidationException;
use Maggie\Core\Entity\User;
use Maggie\Core\Observability\SentryEventFilter;
use Maggie\Core\Observability\SentryUserListener;
use PHPUnit\Framework\Attributes\DataProvider;
use Sentry\ClientBuilder;
use Sentry\Event;
use Sentry\EventHint;
use Sentry\Options;
use Sentry\State\HubInterface;
use Sentry\State\Scope;
use Sentry\Transport\Result;
use Sentry\Transport\ResultStatus;
use Sentry\Transport\TransportInterface;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpKernel\Exception\HttpException;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;
use Symfony\Component\Security\Core\Authentication\Token\UsernamePasswordToken;
use Symfony\Component\Security\Core\Exception\AccessDeniedException;
use Symfony\Component\Security\Core\Exception\AuthenticationException;
use Symfony\Component\Security\Core\Exception\AuthenticationServiceException;
use Symfony\Component\Security\Http\Authenticator\AuthenticatorInterface;
use Symfony\Component\Security\Http\Authenticator\Passport\Badge\UserBadge;
use Symfony\Component\Security\Http\Authenticator\Passport\SelfValidatingPassport;
use Symfony\Component\Security\Http\Event\LoginSuccessEvent;
use Symfony\Component\Serializer\Exception\NotNormalizableValueException;
use Symfony\Component\Validator\ConstraintViolationList;

/**
 * Production errors reach GlitchTip through the Sentry SDK: nothing leaves
 * without a DSN, every event says it comes from the api, 4xx stay home and the
 * user travels as an id only.
 */
final class SentryConfigurationTest extends WebTestCase
{
    public function testNothingIsSentWithoutADsn(): void
    {
        self::bootKernel();
        $options = $this->hub()->getClient()?->getOptions();

        self::assertNotNull($options, 'The bundle must be loaded in every environment.');
        self::assertNull($options->getDsn(), 'SENTRY_DSN is empty outside prod: the SDK must have nowhere to send to.');
    }

    public function testEveryEventIsTaggedApiAndCarriesNoPersonalData(): void
    {
        self::bootKernel();
        $options = $this->hub()->getClient()?->getOptions();

        self::assertNotNull($options);
        self::assertSame(['component' => 'api'], $options->getTags());
        self::assertFalse($options->shouldSendDefaultPii());
        self::assertSame('never', $options->getMaxRequestBodySize());
        self::assertSame('prod', $options->getEnvironment());

        $collection = $options->getDataCollection();
        self::assertNotNull($collection);
        self::assertFalse($collection->shouldCollectUserInfo());
        self::assertTrue($collection->getUrlQueryParams()->isOff(), 'A search query can hold what the user typed.');
        self::assertTrue($collection->getCookies()->isOff());
        self::assertSame([], $collection->getHttpBodies());
        self::assertTrue($collection->getStackFrameVariables()->isOff());
    }

    public function testAnUnhandledErrorIsSentWithItsComponent(): void
    {
        self::bootKernel();
        $transport = $this->captureWithFakeTransport();

        $this->hub()->captureException(new \RuntimeException('boom'));

        self::assertCount(1, $transport->events);
        self::assertSame('api', $transport->events[0]->getTags()['component'] ?? null);
    }

    public function testANotFoundAnswerSendsNothing(): void
    {
        $client = self::createClient();
        $transport = $this->captureWithFakeTransport();

        $client->request('GET', '/api/this-route-does-not-exist');

        self::assertResponseStatusCodeSame(404);
        self::assertSame([], $transport->events, 'A 404 is an answer, not a production error.');
    }

    /**
     * @return iterable<string, array{\Throwable}>
     */
    public static function clientErrors(): iterable
    {
        yield '404' => [new NotFoundHttpException()];
        yield '400 from HttpException' => [new HttpException(400)];
        yield '401' => [new AuthenticationException()];
        yield '403' => [new AccessDeniedException()];
        yield '422 validation' => [new ValidationException(new ConstraintViolationList())];
        yield '400 serializer (exception_to_status)' => [NotNormalizableValueException::createForUnexpectedDataType('bad', 'x', ['int'])];
    }

    #[DataProvider('clientErrors')]
    public function testAClientErrorIsDropped(\Throwable $exception): void
    {
        self::bootKernel();

        self::assertNull($this->filter()(Event::createEvent(), EventHint::fromArray(['exception' => $exception])));
    }

    /**
     * @return iterable<string, array{\Throwable}>
     */
    public static function serverErrors(): iterable
    {
        yield 'unhandled' => [new \RuntimeException('boom')];
        yield '500' => [new HttpException(500)];
        yield '503' => [new HttpException(503)];
        yield 'broken user provider' => [new AuthenticationServiceException()];
    }

    #[DataProvider('serverErrors')]
    public function testAServerErrorIsKept(\Throwable $exception): void
    {
        self::bootKernel();
        $event = Event::createEvent();

        self::assertSame($event, $this->filter()($event, EventHint::fromArray(['exception' => $exception])));
    }

    public function testAnEventWithoutExceptionIsKept(): void
    {
        $event = Event::createEvent();

        self::assertSame($event, (new SentryEventFilter())($event, null));
    }

    public function testTheUserTravelsAsAnIdOnly(): void
    {
        self::bootKernel();
        $transport = $this->captureWithFakeTransport();
        $user = (new User())->setEmail('owner@example.com')->setName('Owner');

        $listener = self::getContainer()->get(SentryUserListener::class);
        self::assertInstanceOf(SentryUserListener::class, $listener);
        $listener(new LoginSuccessEvent(
            $this->createStub(AuthenticatorInterface::class),
            new SelfValidatingPassport(new UserBadge('owner@example.com', static fn () => $user)),
            new UsernamePasswordToken($user, 'api', ['ROLE_USER']),
            new Request(),
            null,
            'api',
        ));
        $this->hub()->captureException(new \RuntimeException('boom'));

        self::assertCount(1, $transport->events);
        $sent = $transport->events[0]->getUser();
        self::assertNotNull($sent);
        self::assertSame((string) $user->getId(), $sent->getId());
        self::assertNull($sent->getEmail());
        self::assertNull($sent->getUsername());
        self::assertNull($sent->getIpAddress());
    }

    protected function tearDown(): void
    {
        if (null !== self::$kernel) {
            $this->hub()->configureScope(static fn (Scope $scope) => $scope->clear());
        }
        parent::tearDown();
    }

    private function hub(): HubInterface
    {
        $hub = self::getContainer()->get(HubInterface::class);
        self::assertInstanceOf(HubInterface::class, $hub);

        return $hub;
    }

    private function filter(): SentryEventFilter
    {
        $filter = self::getContainer()->get(SentryEventFilter::class);
        self::assertInstanceOf(SentryEventFilter::class, $filter);

        return $filter;
    }

    /**
     * Binds a client with a DSN, the app's own options and a transport that
     * keeps the events instead of sending them — the prod path, minus the wire.
     */
    private function captureWithFakeTransport(): FakeSentryTransport
    {
        $hub = $this->hub();
        $options = $hub->getClient()?->getOptions();
        self::assertNotNull($options);
        $options = new Options([
            'dsn' => 'http://public@glitchtip.test/1',
            'environment' => $options->getEnvironment(),
            'tags' => $options->getTags(),
            'send_default_pii' => $options->shouldSendDefaultPii(),
            'before_send' => $options->getBeforeSendCallback(),
            'data_collection' => $options->getDataCollection(),
            // No global PHP error handler from this throwaway client.
            'default_integrations' => false,
        ]);

        $transport = new FakeSentryTransport();
        $hub->bindClient((new ClientBuilder($options))->setTransport($transport)->getClient());

        return $transport;
    }
}

final class FakeSentryTransport implements TransportInterface
{
    /** @var list<Event> */
    public array $events = [];

    public function send(Event $event): Result
    {
        $this->events[] = $event;

        return new Result(ResultStatus::success(), $event);
    }

    public function close(?int $timeout = null): Result
    {
        return new Result(ResultStatus::success());
    }
}
