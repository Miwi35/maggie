<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Command\CheckBankConsentsCommand;
use Maggie\Finance\Entity\BankConnection;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Application;
use Symfony\Component\Console\Tester\CommandTester;

/**
 * `app:finance:check-consents` — the only thing that tells a user their bank
 * access is about to stop. Without it the figures just go stale in silence.
 */
final class CheckBankConsentsCommandTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    private CommandTester $tester;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $application = new Application();
        $application->add(self::getContainer()->get(CheckBankConsentsCommand::class));

        $this->tester = new CommandTester($application->find('app:finance:check-consents'));
    }

    public function testAConsentRunningOutSoonBecomesANotificationLinkingToTheBankPage(): void
    {
        $this->loadFixtures('CheckBankConsentsCommandTest.yaml');

        $this->runCommand();

        $connection = $this->connection('Banque Proche');
        $notification = $this->notificationFor('Banque Proche');
        self::assertNotNull($notification, 'the expiring consent produced no notification');
        self::assertSame(NotificationType::ConsentExpiring, $notification->getType());
        self::assertSame("L'accès à Banque Proche expire dans 3 jours — reconnectez la banque", $notification->getTitle());
        self::assertSame('/finance/banks?connection='.$connection->getId(), $notification->getRelatedEntityIri());
        self::assertNull($notification->getReadAt());
        self::assertSame('consent-owner@example.com', $notification->getUser()->getEmail());

        $this->assertMercureUpdatePublished('/api/notifications/');
        $this->assertElasticsearchIndexDispatched(Notification::class);
    }

    public function testAConsentThatAlreadyRanOutIsSaidToHaveExpired(): void
    {
        $this->loadFixtures('CheckBankConsentsCommandTest.yaml');

        $this->runCommand();

        self::assertSame(
            "L'accès à Banque Échue a expiré — reconnectez la banque",
            $this->notificationFor('Banque Échue')?->getTitle(),
        );
    }

    /** The complement of what fired: far away, no date, revoked, pending. */
    public function testOnlyTheOwnersWhoseConsentIsAtStakeAreWarned(): void
    {
        $this->loadFixtures('CheckBankConsentsCommandTest.yaml');

        $this->runCommand();

        self::assertEqualsCanonicalizing(
            [
                "L'accès à Banque Proche expire dans 3 jours — reconnectez la banque",
                "L'accès à Banque Échue a expiré — reconnectez la banque",
                "L'accès à Banque Du Voisin expire dans 5 jours — reconnectez la banque",
            ],
            array_map(
                static fn (Notification $notification) => $notification->getTitle(),
                $this->entityManager()->getRepository(Notification::class)->findAll(),
            ),
        );
        self::assertSame(
            'consent-other@example.com',
            $this->notificationFor('Banque Du Voisin')?->getUser()->getEmail(),
            'the neighbour\'s warning must land in their own inbox',
        );
    }

    public function testASecondRunDoesNotWarnAgain(): void
    {
        $this->loadFixtures('CheckBankConsentsCommandTest.yaml');

        $this->runCommand();
        $this->runCommand();

        // The cron runs every day for a week before the expiry: without the
        // dedup the owner would be warned seven times for the same consent.
        self::assertCount(3, $this->entityManager()->getRepository(Notification::class)->findAll());
        self::assertStringContainsString('No bank consent to warn about.', $this->tester->getDisplay());
    }

    public function testARenewedConsentCanBeWarnedAboutAgainWhenItRunsOutLater(): void
    {
        $this->loadFixtures('CheckBankConsentsCommandTest.yaml');
        $this->runCommand();

        // The first warning belongs to a consent that was since replaced:
        // renewed months later, the new one runs out in two days.
        $this->backdate($this->notificationFor('Banque Proche'), new \DateTimeImmutable('-100 days'));
        $connection = $this->connection('Banque Proche');
        $connection->activate('renewed-session', new \DateTimeImmutable('+2 days'));
        $this->entityManager()->flush();

        $this->runCommand();

        self::assertCount(
            2,
            $this->entityManager()->getRepository(Notification::class)->findBy(
                ['relatedEntityIri' => '/finance/banks?connection='.$connection->getId()],
            ),
        );
    }

    public function testItSaysSoWhenNothingIsDue(): void
    {
        $this->tester->execute([]);

        $this->tester->assertCommandIsSuccessful();
        self::assertStringContainsString('No bank consent to warn about.', $this->tester->getDisplay());
    }

    private function runCommand(): void
    {
        $this->tester->execute([]);
        $this->tester->assertCommandIsSuccessful();
        $this->entityManager()->clear();
    }

    private function backdate(Notification $notification, \DateTimeImmutable $at): void
    {
        $this->entityManager()->getConnection()->executeStatement(
            'UPDATE notification SET created_at = :at WHERE id = :id',
            ['at' => $at->format('Y-m-d H:i:sP'), 'id' => $notification->getId()->toRfc4122()],
        );
        $this->entityManager()->clear();
    }

    private function connection(string $bankName): BankConnection
    {
        $connection = $this->entityManager()->getRepository(BankConnection::class)->findOneBy(['bankName' => $bankName]);
        self::assertInstanceOf(BankConnection::class, $connection);

        return $connection;
    }

    private function notificationFor(string $bankName): ?Notification
    {
        foreach ($this->entityManager()->getRepository(Notification::class)->findAll() as $notification) {
            if (str_contains($notification->getTitle(), $bankName)) {
                return $notification;
            }
        }

        return null;
    }

    private function entityManager(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
