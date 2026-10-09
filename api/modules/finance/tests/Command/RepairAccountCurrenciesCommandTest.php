<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Command\RepairAccountCurrenciesCommand;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\UseCase\RepairAccountCurrencies;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Application;
use Symfony\Component\Console\Tester\CommandTester;

/**
 * `app:finance:repair-account-currencies` — gives back a real currency to an
 * account a bank once left with « XXX » (MAG-376).
 */
final class RepairAccountCurrenciesCommandTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('RepairAccountCurrenciesCommandTest.yaml');
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function tester(): CommandTester
    {
        $application = new Application();
        $application->add(new RepairAccountCurrenciesCommand(self::getContainer()->get(RepairAccountCurrencies::class)));

        return new CommandTester($application->find('app:finance:repair-account-currencies'));
    }

    private function account(string $name, string $currency): Account
    {
        $account = (new Account())
            ->setUser($this->em()->find(User::class, $this->getFixture('owner')->getId()))
            ->setName($name)
            ->setCurrency($currency);
        $this->em()->persist($account);
        $this->em()->flush();

        return $account;
    }

    private function movement(Account $account, string $currency): void
    {
        $this->em()->persist((new Transaction())
            ->setUser($account->getUser())
            ->setAccount($account)
            ->setLabel('MOVEMENT')
            ->setAmountCents(-100)
            ->setCurrency($currency)
            ->setBookedAt(new \DateTimeImmutable('2026-09-01')));
        $this->em()->flush();
    }

    /** @return array<string, string> */
    private function currencies(): array
    {
        $this->em()->clear();
        $found = [];
        foreach ($this->em()->getRepository(Account::class)->findAll() as $account) {
            $found[$account->getName()] = $account->getCurrency();
        }

        ksort($found);

        return $found;
    }

    private function brokenHistory(): void
    {
        $withMovements = $this->account('Compte principal', 'XXX');
        $this->movement($withMovements, 'CHF');
        $this->movement($withMovements, 'CHF');
        $this->movement($withMovements, 'EUR');
        $this->account('Compte sans mouvement', 'XTS');
        $this->account('Livret', 'EUR');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testADryRunReportsTheAccountsWithoutARealCurrencyAndWritesNothing(): void
    {
        $this->brokenHistory();

        $tester = $this->tester();
        $tester->execute(['--dry-run' => true]);
        $tester->assertCommandIsSuccessful();

        $display = $tester->getDisplay();
        self::assertStringContainsString('Compte principal', $display);
        self::assertStringContainsString('Compte sans mouvement', $display);
        self::assertStringNotContainsString('Livret', $display);

        self::assertSame(
            ['Compte principal' => 'XXX', 'Compte sans mouvement' => 'XTS', 'Livret' => 'EUR'],
            $this->currencies(),
        );
    }

    public function testItGivesTheCurrencyOfTheMovementsOrElseEuro(): void
    {
        $this->brokenHistory();

        $tester = $this->tester();
        $tester->execute([]);
        $tester->assertCommandIsSuccessful();

        self::assertSame(
            ['Compte principal' => 'CHF', 'Compte sans mouvement' => 'EUR', 'Livret' => 'EUR'],
            $this->currencies(),
        );

        $this->assertMercureUpdatePublished('/api/accounts/');
        $this->assertElasticsearchIndexDispatched(Account::class);
    }

    public function testWhenEveryAccountIsFineItSaysSoAndChangesNothing(): void
    {
        $this->account('Livret', 'EUR');
        $this->account('Compte suisse', 'CHF');

        $tester = $this->tester();
        $tester->execute(['--dry-run' => true]);

        self::assertStringContainsString('Aucun compte sans devise réelle', $tester->getDisplay());

        $tester->execute([]);
        self::assertStringContainsString('Aucun compte sans devise réelle', $tester->getDisplay());
        self::assertSame(['Compte suisse' => 'CHF', 'Livret' => 'EUR'], $this->currencies());
    }

    public function testRunningItTwiceChangesNothingMore(): void
    {
        $this->brokenHistory();

        $this->tester()->execute([]);
        $this->resetMercure();
        $this->resetAsyncTransport();

        $second = $this->tester();
        $second->execute([]);

        self::assertStringContainsString('Aucun compte sans devise réelle', $second->getDisplay());
    }
}
