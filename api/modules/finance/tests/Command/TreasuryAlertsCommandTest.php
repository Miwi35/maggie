<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Enum\ProactionStatus;
use Maggie\Finance\Command\TreasuryAlertsCommand;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Qualification;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Test\ClockSensitiveTrait;
use Symfony\Component\Console\Application;
use Symfony\Component\Console\Tester\CommandTester;

/**
 * `app:finance:treasury-alerts` (MAG-281): when the salary is late and an
 * overdraft is forecast within a week, Maggie is asked, once a day, to warn the
 * user and propose an advance from savings. The alert is a proaction in the
 * user's internal agenda; the agent runs it and writes in the conversation.
 *
 * Today is Saturday 31 Oct 2026 at 09:00 Paris. The salary was expected on the
 * 25th: whoever is not paid is 6 days late. The Courant holds 200 €, the
 * Livret 6 000 €; a planned rent of 300 € puts the Courant 100 € under.
 */
final class TreasuryAlertsCommandTest extends KernelTestCase
{
    use ClockSensitiveTrait;
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    private CommandTester $tester;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::mockTime('2026-10-31 09:00:00 Europe/Paris');
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $application = new Application();
        $application->add(self::getContainer()->get(TreasuryAlertsCommand::class));

        $this->tester = new CommandTester($application->find('app:finance:treasury-alerts'));
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures('TreasuryAlertsCommandTest.yaml');
    }

    public function testALateSalaryWithAnOverdraftInSixDaysFilesOneAlertThatStatesTheDelayTheDateAndTheAdvice(): void
    {
        $this->paidLastMonth('late');
        $this->rent('late', '2026-11-06');

        $this->runCommand();

        $alerts = $this->alertsOf('late');
        self::assertCount(1, $alerts);
        $alert = $alerts[0];
        self::assertSame(TreasuryAlertsCommand::SUMMARY, $alert->getSummary());
        self::assertSame(ProactionStatus::Pending, $alert->getExecutionStatus());
        self::assertSame('2026-10-31 09:00', $alert->getStartAt()?->setTimezone(new \DateTimeZone('Europe/Paris'))->format('Y-m-d H:i'));

        $prompt = $alert->getPrompt();
        self::assertStringContainsString('6 jour(s) de retard', $prompt, 'the delay');
        self::assertStringContainsString('Le compte courant passerait à découvert le 2026-11-06', $prompt, 'the overdraft date');
        self::assertStringContainsString('Avance conseillée : 100,00 €, depuis le compte « Livret late »', $prompt, 'the advice and its source');
        self::assertStringContainsString('source : get_treasury_outlook', $prompt);

        $this->assertMercureUpdatePublished('/proactions/');
        $this->assertElasticsearchIndexDispatched(Proaction::class);
    }

    /** Maggie only proposes: the prompt forbids her to execute or claim the transfer, and keeps off the envelope alert's ground. */
    public function testThePromptMakesMaggieProposeNeverExecuteAndStaysApartFromTheScoreAndTheReview(): void
    {
        $this->paidLastMonth('late');
        $this->rent('late', '2026-11-06');

        $this->runCommand();

        $prompt = $this->alertsOf('late')[0]->getPrompt();
        self::assertStringContainsString('Tu ne fais que proposer', $prompt);
        self::assertStringContainsString('n\'exécute aucun virement', $prompt);
        self::assertStringContainsString('ne dis jamais qu\'il est fait', $prompt);
        self::assertStringContainsString('la rendre dès que le salaire arrivera', $prompt);
        self::assertStringContainsString('Ne parle ni du score ni de la revue mensuelle', $prompt);
    }

    public function testNothingIsFiledWhenTheSalaryHasArrived(): void
    {
        $this->paidLastMonth('arrived');
        $this->salary('arrived', 495000, '2026-10-26');
        $this->rent('arrived', '2026-11-06');

        $this->runCommand();

        self::assertSame([], $this->allAlerts());
        self::assertStringContainsString('No treasury alert to file.', $this->tester->getDisplay());
    }

    public function testNothingIsFiledWhenTheSalaryIsLateButNoOverdraftIsForecast(): void
    {
        $this->paidLastMonth('flush');

        $this->runCommand();

        self::assertSame([], $this->allAlerts());
    }

    /** @return iterable<string, array{string, bool}> */
    public static function overdraftDates(): iterable
    {
        yield 'in 6 days' => ['2026-11-06', true];
        yield 'in 7 days, the last one that counts' => ['2026-11-07', true];
        yield 'in 8 days' => ['2026-11-08', false];
        yield 'in 10 days' => ['2026-11-10', false];
    }

    #[DataProvider('overdraftDates')]
    public function testOnlyAnOverdraftWithinSevenDaysIsWorthAnAlert(string $rentDay, bool $alerted): void
    {
        $this->paidLastMonth('far');
        $this->rent('far', $rentDay);

        $this->runCommand();

        self::assertCount($alerted ? 1 : 0, $this->alertsOf('far'));
    }

    public function testNothingIsFiledWhenTheSalaryExpectedDayIsNotSet(): void
    {
        $this->rent('unset', '2026-11-06');

        $this->runCommand();

        self::assertSame([], $this->allAlerts());
    }

    public function testTwoRunsTheSameDayFileASingleAlert(): void
    {
        $this->paidLastMonth('late');
        $this->rent('late', '2026-11-06');

        $this->runCommand();
        $this->runCommand();
        self::mockTime('2026-10-31 18:30:00 Europe/Paris');
        $this->runCommand();

        self::assertCount(1, $this->alertsOf('late'));
    }

    public function testTheNextDayFilesANewAlertWhileTheSituationLasts(): void
    {
        $this->paidLastMonth('late');
        $this->rent('late', '2026-11-06');
        $this->runCommand();

        self::mockTime('2026-11-01 09:00:00 Europe/Paris');
        $this->runCommand();

        $alerts = $this->alertsOf('late');
        self::assertCount(2, $alerts);
        self::assertStringContainsString('7 jour(s) de retard', $alerts[1]->getPrompt());
    }

    public function testEachUserOnlyReceivesTheirOwnAlert(): void
    {
        foreach (['late', 'other'] as $who) {
            $this->paidLastMonth($who);
            $this->rent($who, '2026-11-06');
        }

        $this->runCommand();

        self::assertCount(1, $this->alertsOf('late'));
        self::assertCount(1, $this->alertsOf('other'));
        self::assertStringContainsString('Livret late', $this->alertsOf('late')[0]->getPrompt());
        self::assertStringNotContainsString('Livret other', $this->alertsOf('late')[0]->getPrompt());
        self::assertStringContainsString('Livret other', $this->alertsOf('other')[0]->getPrompt());
        self::assertStringNotContainsString('Livret late', $this->alertsOf('other')[0]->getPrompt());
    }

    public function testItSaysSoWhenNothingIsDue(): void
    {
        $this->tester->execute([]);

        $this->tester->assertCommandIsSuccessful();
        self::assertStringContainsString('No treasury alert to file.', $this->tester->getDisplay());
    }

    private function runCommand(): void
    {
        $this->tester->execute([]);
        $this->tester->assertCommandIsSuccessful();
        $this->em->clear();
    }

    /** September's salary is in, so only October's is late. */
    private function paidLastMonth(string $who): void
    {
        $this->salary($who, 495000, '2026-09-25');
    }

    private function salary(string $who, int $amountCents, string $bookedAt): void
    {
        $category = $this->getFixture('salary_'.$who);
        \assert($category instanceof Category);

        $this->book($who, $amountCents, $bookedAt, 'VIR ACME SALAIRE')->qualify(Qualification::none($category), null);
        $this->flushWithoutTransactionEffects($this->em);
    }

    /** A rent of 300 € planned on the Courant: the 200 € on it end 100 € under. */
    private function rent(string $who, string $bookedAt): void
    {
        $this->book($who, -30000, $bookedAt, 'Loyer')->setStatus(TransactionStatus::Planned);
        $this->flushWithoutTransactionEffects($this->em);
    }

    private function book(string $who, int $amountCents, string $bookedAt, string $label): Transaction
    {
        $account = $this->getFixture('checking_'.$who);
        \assert($account instanceof Account);

        $transaction = (new Transaction())
            ->setUser($account->getUser())
            ->setAccount($account)
            ->setAmountCents($amountCents)
            ->setBookedAt(new \DateTimeImmutable($bookedAt))
            ->setLabel($label);
        $this->em->persist($transaction);
        $this->flushWithoutTransactionEffects($this->em);

        return $transaction;
    }

    /** @return list<Proaction> */
    private function allAlerts(): array
    {
        return array_values($this->em->getRepository(Proaction::class)->findAll());
    }

    /** @return list<Proaction> oldest first */
    private function alertsOf(string $who): array
    {
        $email = "treasury-alerts-{$who}@example.com";
        $alerts = array_values(array_filter(
            $this->allAlerts(),
            static fn (Proaction $proaction): bool => $email === $proaction->getAgenda()->getUser()->getEmail(),
        ));
        usort($alerts, static fn (Proaction $a, Proaction $b): int => $a->getStartAt() <=> $b->getStartAt());

        return $alerts;
    }
}
