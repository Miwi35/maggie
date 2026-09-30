<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Mercure;

use Maggie\Core\Mercure\InvalidMercureSecretException;
use Maggie\Core\Mercure\Listener\MercureSecretListener;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpKernel\Event\RequestEvent;
use Symfony\Component\HttpKernel\HttpKernelInterface;
use Symfony\Component\HttpKernel\KernelEvents;

final class MercureSecretBootTest extends KernelTestCase
{
    private string|false $previous;

    protected function setUp(): void
    {
        $this->previous = $_SERVER['MERCURE_JWT_SECRET'] ?? false;
    }

    protected function tearDown(): void
    {
        if (false === $this->previous) {
            unset($_SERVER['MERCURE_JWT_SECRET'], $_ENV['MERCURE_JWT_SECRET']);
        } else {
            $_SERVER['MERCURE_JWT_SECRET'] = $_ENV['MERCURE_JWT_SECRET'] = $this->previous;
        }
        parent::tearDown();
    }

    public function testTheBootedApplicationRefusesRequestsWithATooShortSecret(): void
    {
        $_SERVER['MERCURE_JWT_SECRET'] = $_ENV['MERCURE_JWT_SECRET'] = 'e2e-mercure-secret';
        self::bootKernel();

        $this->expectException(InvalidMercureSecretException::class);
        $this->expectExceptionMessage('MERCURE_JWT_SECRET is 18 bytes long');

        self::getContainer()->get('event_dispatcher')->dispatch(
            new RequestEvent(self::$kernel, new Request(), HttpKernelInterface::MAIN_REQUEST),
            KernelEvents::REQUEST,
        );
    }

    public function testTheBootedApplicationAcceptsRequestsWithTheConfiguredSecret(): void
    {
        self::bootKernel();

        // Would throw on a too-short secret; the test env ships a valid one.
        self::getContainer()->get(MercureSecretListener::class)->onKernelRequest(
            new RequestEvent(self::$kernel, new Request(), HttpKernelInterface::MAIN_REQUEST),
        );

        $this->expectNotToPerformAssertions();
    }
}
