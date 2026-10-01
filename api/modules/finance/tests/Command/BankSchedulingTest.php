<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Command;

use App\Tests\Support\FixtureLoaderTrait;
use Symfony\Bundle\FrameworkBundle\Console\Application;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Tester\CommandTester;

/**
 * Nothing runs the bank commands but the crontab baked into the image (the k8s
 * cron pod uses the same image), so a command renamed without the crontab line
 * is silent. The crontab is outside the api/ tree the test container mounts:
 * this guards the names, the line itself is read in review.
 */
final class BankSchedulingTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    public function testEveryScheduledCommandExists(): void
    {
        $application = new Application(self::bootKernel());

        foreach (['app:finance:sync', 'app:finance:check-consents'] as $name) {
            self::assertTrue($application->has($name), $name.' is scheduled but not registered');
        }
    }

    public function testTheSyncWithoutAnEmailWalksOnlyTheOwnersOfLiveConnections(): void
    {
        self::bootKernel();
        $this->loadFixtures('BankSchedulingTest.yaml');

        $tester = new CommandTester((new Application(self::$kernel))->find('app:finance:sync'));
        $tester->execute(['--write' => true]);

        $display = $tester->getDisplay();
        $tester->assertCommandIsSuccessful();
        self::assertStringContainsString('sync-live@example.com', $display);
        self::assertStringContainsString('needs_reconnecting', $display, 'an expired consent is reported, not called');
        self::assertStringNotContainsString('sync-pending@example.com', $display, 'a pending journey is not a live link');
    }
}
