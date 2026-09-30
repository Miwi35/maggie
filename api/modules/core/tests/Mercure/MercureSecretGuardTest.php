<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Mercure;

use Maggie\Core\Mercure\InvalidMercureSecretException;
use Maggie\Core\Mercure\Listener\MercureSecretListener;
use Maggie\Core\Mercure\MercureSecretGuard;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\ConsoleEvents;
use Symfony\Component\Console\Event\ConsoleCommandEvent;
use Symfony\Component\Console\Input\ArrayInput;
use Symfony\Component\Console\Output\NullOutput;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpKernel\Event\RequestEvent;
use Symfony\Component\HttpKernel\HttpKernelInterface;
use Symfony\Component\HttpKernel\KernelEvents;

final class MercureSecretGuardTest extends TestCase
{
    /** @return iterable<string, array{string}> */
    public static function tooShortSecrets(): iterable
    {
        yield 'empty' => [''];
        yield 'the e2e secret that started it (18 bytes)' => ['e2e-mercure-secret'];
        yield 'the old .env.prod.example value (28 bytes)' => ['change-me-mercure-jwt-secret'];
        yield 'one byte short of 256 bits' => [str_repeat('a', 31)];
    }

    #[DataProvider('tooShortSecrets')]
    public function testASecretUnder32BytesRefusesToBootAndNamesTheCause(string $secret): void
    {
        try {
            (new MercureSecretGuard($secret))->assertValid();
            self::fail('A secret shorter than 256 bits must be refused.');
        } catch (InvalidMercureSecretException $e) {
            self::assertStringContainsString('MERCURE_JWT_SECRET', $e->getMessage());
            self::assertStringContainsString((string) strlen($secret).' bytes', $e->getMessage());
            self::assertStringContainsString('32 bytes', $e->getMessage());
            self::assertStringContainsString('openssl rand -hex 32', $e->getMessage());
        }
    }

    public function testASecretOf32BytesIsAccepted(): void
    {
        (new MercureSecretGuard(str_repeat('a', 32)))->assertValid();

        $this->expectNotToPerformAssertions();
    }

    public function testTheLengthIsCountedInBytesNotCharacters(): void
    {
        // 16 characters, 32 bytes: HS256 needs 256 bits of key material.
        (new MercureSecretGuard(str_repeat('é', 16)))->assertValid();

        $this->expectNotToPerformAssertions();
    }

    public function testEveryRequestFailsWhenTheSecretIsTooShort(): void
    {
        $listener = new MercureSecretListener(new MercureSecretGuard('too-short'));
        $event = new RequestEvent(
            $this->createStub(HttpKernelInterface::class),
            new Request(),
            HttpKernelInterface::MAIN_REQUEST,
        );

        $this->expectException(InvalidMercureSecretException::class);
        $this->expectExceptionMessage('MERCURE_JWT_SECRET');

        $listener->onKernelRequest($event);
    }

    public function testEveryConsoleCommandFailsWhenTheSecretIsTooShort(): void
    {
        $listener = new MercureSecretListener(new MercureSecretGuard('too-short'));
        $event = new ConsoleCommandEvent(new Command('app:any'), new ArrayInput([]), new NullOutput());

        $this->expectException(InvalidMercureSecretException::class);

        $listener->onConsoleCommand($event);
    }

    public function testAValidSecretLetsRequestsAndCommandsThrough(): void
    {
        $listener = new MercureSecretListener(new MercureSecretGuard(str_repeat('a', 32)));

        $listener->onKernelRequest(new RequestEvent(
            $this->createStub(HttpKernelInterface::class),
            new Request(),
            HttpKernelInterface::MAIN_REQUEST,
        ));
        $listener->onConsoleCommand(new ConsoleCommandEvent(new Command('app:any'), new ArrayInput([]), new NullOutput()));

        $this->expectNotToPerformAssertions();
    }

    public function testTheListenerIsSubscribedToRequestsBeforeRoutingAndToCommands(): void
    {
        $reflection = new \ReflectionClass(MercureSecretListener::class);
        $events = [];
        foreach ($reflection->getMethods() as $method) {
            foreach ($method->getAttributes(\Symfony\Component\EventDispatcher\Attribute\AsEventListener::class) as $attribute) {
                $events[$attribute->getArguments()['event']] = $attribute->getArguments()['priority'] ?? 0;
            }
        }

        self::assertGreaterThan(32, $events[KernelEvents::REQUEST], 'Must run before the router and the firewall.');
        self::assertArrayHasKey(ConsoleEvents::COMMAND, $events);
    }
}
