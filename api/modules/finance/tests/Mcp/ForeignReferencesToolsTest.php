<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Entity\SafetyCushion;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Mcp\Tool\ManageCategorizationRulesTool;
use Maggie\Finance\Mcp\Tool\ManageEnvelopesTool;
use Maggie\Finance\Message\UpdateSafetyCushionCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

/** A refused foreign reference leaves the other user's objects and the cushion untouched. */
class ForeignReferencesToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('foreign_create_references.yaml');
        $this->loginFixtureUser();
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    /** @param class-string $class */
    private function rows(string $class): int
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository($class)->count([]);
    }

    /**
     * @param class-string       $entity
     * @param callable(): string $call
     */
    private function assertRefused(string $entity, callable $call, string $expected): void
    {
        $before = $this->rows($entity);

        $result = json_decode($call(), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $result);
        self::assertStringContainsString($expected, $result['error']);
        self::assertSame($before, $this->rows($entity));
        $this->assertMercureUpdateCount(0);
        self::assertSame([], $this->getAsyncTransport()->getSent());
    }

    public function testBudgetingAnotherUsersCategoryAlreadyBudgetedLeavesTheEnvelopeAlone(): void
    {
        $this->assertRefused(Envelope::class, fn () => self::getContainer()->get(ManageEnvelopesTool::class)(
            'set',
            categoryId: $this->id('foreign_category'),
            amountCents: 1,
            mode: 'monthly',
            year: 2026,
            month: 7,
        ), 'Category not found');

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertSame(40000, $em->find(Envelope::class, $this->getFixture('foreign_envelope')->getId())->getAmountCents());
    }

    public function testLearningFromAnotherUsersTransactionIsRefused(): void
    {
        $this->assertRefused(CategorizationRule::class, fn () => self::getContainer()->get(ManageCategorizationRulesTool::class)(
            'learn',
            transactionId: $this->id('foreign_transaction'),
            categoryId: $this->id('own_category'),
        ), 'Transaction not found');

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $transaction = $em->find(Transaction::class, $this->getFixture('foreign_transaction')->getId());
        self::assertSame('Catégorie étrangère', $transaction->getCategory()->getName());
    }

    public function testConfiguringAnotherUsersCushionIsRefused(): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $user = $this->getFixture('test_user');

        try {
            self::getContainer()->get(MessageBusInterface::class)->dispatch(new UpdateSafetyCushionCommand(
                userId: (string) $user->getId(),
                safetyCushionId: $this->id('foreign_cushion'),
                targetMonths: 12,
            ));
            self::fail('The foreign cushion must not be resolved.');
        } catch (HandlerFailedException $e) {
            self::assertStringContainsString('Safety cushion not found', $e->getPrevious()->getMessage());
        }

        $em->clear();
        self::assertSame(3, $em->find(SafetyCushion::class, $this->getFixture('foreign_cushion')->getId())->getTargetMonths());
        $this->assertMercureUpdateCount(0);
    }
}
