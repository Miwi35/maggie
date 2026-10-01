<?php

namespace Maggie\Calendar\Tests\Command;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Service\GoogleCalendarApiClient;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\Console\Application;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Tester\CommandTester;

/**
 * Renewing an expiring Google push channel.
 *
 * Google stops pushing when a channel expires, so this cron is the only thing
 * keeping a connected agenda live between syncs. Closing the old channel is
 * best-effort on purpose: a connection that failed halfway (MAG-148) leaves an
 * id Google has already closed, and throwing on it would mean the agenda never
 * gets a channel again.
 */
class GoogleCalendarRenewWatchCommandTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private CommandTester $tester;
    /** @var list<array{string, string}> */
    private array $stopWatchCalls = [];

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('GoogleCalendarRenewWatchCommandTest.yaml');
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function stubGoogle(bool $stopWatchFails = false): void
    {
        $this->stopWatchCalls = [];
        $apiClient = $this->createStub(GoogleCalendarApiClient::class);
        $apiClient->method('stopWatch')->willReturnCallback(
            function (User $user, string $channelId, string $resourceId) use ($stopWatchFails): void {
                $this->stopWatchCalls[] = [$channelId, $resourceId];
                if ($stopWatchFails) {
                    throw new \RuntimeException('Channel not found');
                }
            },
        );
        $apiClient->method('watchEvents')->willReturn([
            'channelId' => 'channel-new',
            'resourceId' => 'resource-new',
            'expiration' => 1_900_000_000_000,
        ]);
        self::getContainer()->set(GoogleCalendarApiClient::class, $apiClient);

        $application = new Application(self::$kernel);
        $this->tester = new CommandTester($application->find('maggie:google-calendar:renew-watch'));
    }

    private function reloadAgenda(): Agenda
    {
        $this->em()->clear();

        return $this->em()->getRepository(Agenda::class)->findOneBy(['name' => 'Concerts']);
    }

    public function testClosesTheOldChannelAndStoresTheNewOne(): void
    {
        $this->stubGoogle();

        $this->tester->execute([]);

        $this->tester->assertCommandIsSuccessful();
        self::assertSame([['channel-old', 'resource-old']], $this->stopWatchCalls);
        self::assertStringContainsString('Renewed 1 watch channel', $this->tester->getDisplay());

        $agenda = $this->reloadAgenda();
        self::assertSame('channel-new', $agenda->getGoogleWatchChannelId());
        self::assertSame('resource-new', $agenda->getGoogleWatchResourceId());
        self::assertGreaterThan(new \DateTimeImmutable('+1 hour'), $agenda->getGoogleWatchExpiresAt());
    }

    public function testStillRenewsWhenGoogleNoLongerKnowsTheOldChannel(): void
    {
        $this->stubGoogle(stopWatchFails: true);

        $this->tester->execute([]);

        $this->tester->assertCommandIsSuccessful();
        self::assertStringContainsString('Renewed 1 watch channel', $this->tester->getDisplay());
        self::assertSame(
            'channel-new',
            $this->reloadAgenda()->getGoogleWatchChannelId(),
            'A channel id Google has already closed must not stop the renewal',
        );
    }

    public function testLeavesAgendasWhoseChannelHasTimeLeftAlone(): void
    {
        $this->em()->getRepository(Agenda::class)
            ->findOneBy(['name' => 'Concerts'])
            ->setGoogleWatchExpiresAt(new \DateTimeImmutable('+10 days'));
        $this->em()->flush();

        $this->stubGoogle();
        $this->tester->execute([]);

        $this->tester->assertCommandIsSuccessful();
        self::assertSame([], $this->stopWatchCalls);
        self::assertStringContainsString('No watch channels need renewal', $this->tester->getDisplay());
        self::assertSame('channel-old', $this->reloadAgenda()->getGoogleWatchChannelId());
    }
}
