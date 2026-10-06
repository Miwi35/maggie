<?php

namespace Maggie\Finance\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Message\UpdateLoanCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

class UpdateLoanHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('clearable_fields.yaml');
    }

    private function userId(): string
    {
        return (string) $this->getFixture('test_user')->getId();
    }

    private function dispatch(UpdateLoanCommand $command): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch($command);
    }

    private function reload(): Loan
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Loan::class)->find($this->getFixture('car')->getId());
    }

    public function testClearingTheLenderLeavesTheFiguresUntouched(): void
    {
        $this->dispatch(new UpdateLoanCommand(
            userId: $this->userId(),
            loanId: (string) $this->getFixture('car')->getId(),
            clearFields: ['lender'],
        ));

        $loan = $this->reload();
        self::assertNull($loan->getLender());
        self::assertSame('Crédit auto', $loan->getName());
        self::assertSame(240000, $loan->getPrincipalRemainingCents());
        self::assertSame(20000, $loan->getMonthlyPaymentCents());
        $this->assertMercureUpdatePublished('/loans/');
        $this->assertElasticsearchIndexDispatched(Loan::class);
    }

    public function testNullFieldsWithoutClearAreLeftUntouched(): void
    {
        $this->dispatch(new UpdateLoanCommand(
            userId: $this->userId(),
            loanId: (string) $this->getFixture('car')->getId(),
            priority: 3,
        ));

        $loan = $this->reload();
        self::assertSame(3, $loan->getPriority());
        self::assertSame('Banque Populaire', $loan->getLender());
    }

    public function testUnknownLoanFails(): void
    {
        $this->expectException(\Throwable::class);
        $this->expectExceptionMessage('Loan not found');

        $this->dispatch(new UpdateLoanCommand(userId: $this->userId(), loanId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', clearFields: ['lender']));
    }
}
