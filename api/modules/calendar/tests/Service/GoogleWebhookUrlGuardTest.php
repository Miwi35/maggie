<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Service\GoogleCalendarApiClient;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/**
 * MAG-193: production ran for days with the example address of api/.env as its
 * webhook URL, so Google pushed to example.com and Maggie only saw a change on
 * the next cron pass. In prod, a channel is never created for an address
 * Google cannot usefully reach.
 */
final class GoogleWebhookUrlGuardTest extends TestCase
{
    /**
     * @return iterable<string, array{string}>
     */
    public static function unusableProdUrls(): iterable
    {
        yield 'the example address of api/.env' => ['https://maggie.example.com/api/calendar/google/webhook'];
        yield 'example.com itself' => ['https://example.com/hook'];
        yield 'plain http' => ['http://maggieai.fr/api/calendar/google/webhook'];
        yield 'no scheme' => ['maggieai.fr/api/calendar/google/webhook'];
        yield 'empty' => [''];
    }

    #[DataProvider('unusableProdUrls')]
    public function testProdRefusesToCreateAChannelForAnUnusableUrl(string $url): void
    {
        $client = new GoogleCalendarApiClient(
            $this->createStub(EntityManagerInterface::class),
            'client-id',
            'client-secret',
            kernelEnvironment: 'prod',
        );

        $this->expectException(\InvalidArgumentException::class);
        $this->expectExceptionMessage('GOOGLE_WEBHOOK_URL');

        $client->watchEvents(new User(), 'calendar@group.calendar.google.com', $url, 'token');
    }

    public function testProdAcceptsTheRealHttpsAddress(): void
    {
        $this->expectNotToPerformAssertions();

        GoogleCalendarApiClient::assertUsableWebhookUrl('https://maggieai.fr/api/calendar/google/webhook', 'prod');
    }

    public function testOtherEnvironmentsKeepTheirLocalAddresses(): void
    {
        $this->expectNotToPerformAssertions();

        GoogleCalendarApiClient::assertUsableWebhookUrl('http://localhost/api/calendar/google/webhook', 'e2e');
        GoogleCalendarApiClient::assertUsableWebhookUrl('https://maggie.example.com/api/calendar/google/webhook', 'dev');
    }
}
