<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Command\DetectRejectionsCommand;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferKind;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Application;
use Symfony\Component\Console\Tester\CommandTester;

/**
 * `app:finance:detect-rejections` — the catch-up over the history stored
 * before rejections were recognised (MAG-350).
 */
final class DetectRejectionsCommandTest extends KernelTestCase
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
        $application->add(self::getContainer()->get(DetectRejectionsCommand::class));

        $this->tester = new CommandTester($application->find('app:finance:detect-rejections'));

        // The world the use case is tested on, shared rather than copied.
        $this->loadFixtures(\dirname(__DIR__).'/UseCase/fixtures/rejections.yaml');
    }

    private function kind(string $ref): TransferKind
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        /** @var Transaction $fixture */
        $fixture = $this->getFixture($ref);

        return $em->find(Transaction::class, $fixture->getId())->getTransferKind();
    }

    public function testADryRunListsThePairsAndWritesNothing(): void
    {
        $this->tester->execute(['--dry-run' => true]);

        $this->tester->assertCommandIsSuccessful();
        self::assertStringContainsString('REJET PRLV ELECTRICITE DE FRANCE', $this->tester->getDisplay());
        self::assertStringContainsString('3 rejet(s) would be paired', $this->tester->getDisplay());
        self::assertSame(TransferKind::None, $this->kind('edf_rejection'));
        self::assertMercureUpdateCount(0);
    }

    public function testItPairsTheHistoryAndASecondRunChangesNothing(): void
    {
        $this->tester->execute([]);

        $this->tester->assertCommandIsSuccessful();
        self::assertStringContainsString('3 rejet(s) apparié(s)', $this->tester->getDisplay());
        self::assertSame(TransferKind::Rejected, $this->kind('edf_debit'));
        self::assertSame(TransferKind::Rejected, $this->kind('ael_rejection'));
        self::assertSame(TransferKind::None, $this->kind('savings_debit'));

        /** @var Transaction $debit */
        $debit = $this->getFixture('edf_debit');
        $this->assertMercureUpdatePublished((string) $debit->getId());
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $debit->getId());

        $this->tester->execute([]);
        self::assertStringContainsString('0 rejet(s) apparié(s)', $this->tester->getDisplay());
    }

    public function testDaysMustBeAPositiveInteger(): void
    {
        self::assertSame(2, $this->tester->execute(['--days' => 'soon']));
    }
}
