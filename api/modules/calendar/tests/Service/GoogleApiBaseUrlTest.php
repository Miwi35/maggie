<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Service\GoogleCalendarApiClient;
use Maggie\Calendar\Service\GoogleTasksApiClient;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\TestCase;

/**
 * MAG-94 points Google Calendar and Tasks at WireMock on the e2e stack. This
 * checks the redirection actually reaches the built service — and, just as
 * importantly, that leaving it unconfigured changes nothing for dev and prod.
 */
final class GoogleApiBaseUrlTest extends TestCase
{
    private const WIREMOCK = 'http://wiremock:8080/google/';

    public function testCalendarServiceUsesTheConfiguredRootUrl(): void
    {
        $client = new GoogleCalendarApiClient(
            $this->createMock(EntityManagerInterface::class),
            'client-id',
            'client-secret',
            self::WIREMOCK,
        );

        self::assertSame(self::WIREMOCK, $client->getCalendarService($this->user())->rootUrl);
    }

    public function testTasksServiceUsesTheConfiguredRootUrl(): void
    {
        $client = new GoogleTasksApiClient(
            $this->createMock(EntityManagerInterface::class),
            'client-id',
            'client-secret',
            self::WIREMOCK,
        );

        self::assertSame(self::WIREMOCK, $client->getTasksService($this->user())->rootUrl);
    }

    public function testAnEmptyBaseUrlLeavesTheLibraryDefault(): void
    {
        // Dev and prod pass an empty string. If this ever started sending
        // requests to "" the failure would look like a network problem.
        $calendar = new GoogleCalendarApiClient(
            $this->createMock(EntityManagerInterface::class),
            'client-id',
            'client-secret',
        );
        $tasks = new GoogleTasksApiClient(
            $this->createMock(EntityManagerInterface::class),
            'client-id',
            'client-secret',
        );

        self::assertStringContainsString('googleapis.com', $calendar->getCalendarService($this->user())->rootUrl);
        self::assertStringContainsString('googleapis.com', $tasks->getTasksService($this->user())->rootUrl);
    }

    private function user(): User
    {
        $user = new User();
        $user->setEmail('google@example.com');
        $user->setGoogleId('google-id');
        $user->setName('Google User');
        $user->setGoogleAccessToken('an-access-token');
        $user->setGoogleTokenExpiresAt(new \DateTimeImmutable('+1 hour'));

        return $user;
    }
}
