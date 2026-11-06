<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Command;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\ActivityStatus;
use Maggie\Core\Enum\Activity;
use Symfony\Bundle\FrameworkBundle\Console\Application;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Tester\CommandTester;

/** `maggie:core:close-open-bilans` — a bilan of a day gone is closed, today's stays resumable (MAG-747). */
final class CloseOpenBilansCommandTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/activity_status.yaml');
        $this->resetMercure();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function startBilan(string $userRef, string $day): void
    {
        $now = new \DateTimeImmutable('now', new \DateTimeZone('UTC'));
        $this->em()->persist((new ActivityStatus())
            ->setUser($this->getFixture($userRef))
            ->setActivity(Activity::Bilan)
            ->setOrigin('day', $day)
            ->setState('{"step":"sante"}')
            ->setStartedAt($now)
            ->setUpdatedAt($now));
        $this->em()->flush();
        $this->resetMercure();
    }

    private function closeBilans(): CommandTester
    {
        $tester = new CommandTester((new Application(self::$kernel))->find('maggie:core:close-open-bilans'));
        $tester->execute([]);
        $this->em()->clear();

        return $tester;
    }

    private function bilanCount(): int
    {
        return $this->em()->getRepository(ActivityStatus::class)->count(['activity' => Activity::Bilan]);
    }

    public function testClosesTheBilanOfYesterdayAndPublishesTheDelete(): void
    {
        $yesterday = (new \DateTimeImmutable('yesterday', new \DateTimeZone('Europe/Paris')))->format('Y-m-d');
        $this->startBilan('test_user', $yesterday);

        $tester = $this->closeBilans();

        $tester->assertCommandIsSuccessful();
        self::assertSame(0, $this->bilanCount());
        $this->assertMercureDeletePublished('/api/activity-status/bilan');
    }

    public function testLeavesTodaysBilanAlone(): void
    {
        $today = (new \DateTimeImmutable('now', new \DateTimeZone('Europe/Paris')))->format('Y-m-d');
        $this->startBilan('test_user', $today);

        $this->closeBilans()->assertCommandIsSuccessful();

        self::assertSame(1, $this->bilanCount());
        $this->assertMercureUpdateCount(0);
    }

    public function testClosesOnlyThePastOnesAndNeverOtherActivities(): void
    {
        $yesterday = (new \DateTimeImmutable('yesterday', new \DateTimeZone('Europe/Paris')))->format('Y-m-d');
        $today = (new \DateTimeImmutable('now', new \DateTimeZone('Europe/Paris')))->format('Y-m-d');
        $this->startBilan('test_user', $yesterday);
        $this->startBilan('other_user', $today);

        $this->closeBilans();

        self::assertSame(1, $this->bilanCount(), 'only the one of the past is closed');
        self::assertSame(1, $this->em()->getRepository(ActivityStatus::class)->count(['activity' => Activity::Cuisine]), 'the cooking in progress is untouched');
    }
}
