<?php

namespace Maggie\Finance\Tests\State;

use ApiPlatform\Metadata\Patch;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Core\Elasticsearch\Hydrator\ElasticsearchEntityHydrator;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\State\UpdateTransactionProcessor;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * A PATCH names its relations by IRI, and API Platform reads each through the
 * resource's item provider — Elasticsearch here, which hands back copies
 * Doctrine does not manage. The admin's sheet sends the whole line back, its
 * unchanged category included: saving it must not trip on that copy (MAG-414).
 */
class UpdateTransactionProcessorTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('one_off_envelope.yaml');
    }

    /** What the item provider makes of an IRI once the document is indexed. */
    private function indexedCopy(object $entity, string $class): object
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        return (new ElasticsearchEntityHydrator($em))->hydrate(
            ['id' => (string) $entity->getId()] + $entity->toSearchDocument(),
            $class,
        );
    }

    public function testSavingALineThatSendsBackItsUnchangedCategoryStoresIt(): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        /** @var Transaction $ticket */
        $ticket = $em->find(Transaction::class, $this->getFixture('ticket')->getId());
        $previous = clone $ticket;

        // The merge-patch: the same category, as an indexed copy, and a note.
        $ticket->setCategory($this->indexedCopy($ticket->getCategory(), Category::class));
        $ticket->setNote('Billet pour le vendredi');

        self::getContainer()->get(UpdateTransactionProcessor::class)
            ->process($ticket, new Patch(), [], ['previous_data' => $previous]);

        $em->clear();
        $stored = $em->find(Transaction::class, $this->getFixture('ticket')->getId());
        self::assertSame('Billet pour le vendredi', $stored->getNote());
        self::assertSame('Loisirs', $stored->getCategory()?->getName());
    }

    public function testAttributingALineThroughAnIndexedCopyOfTheEnvelopeStoresIt(): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        /** @var Transaction $ticket */
        $ticket = $em->find(Transaction::class, $this->getFixture('ticket')->getId());
        $previous = clone $ticket;
        $hellfest = $em->find(Envelope::class, $this->getFixture('hellfest')->getId());

        $ticket->setCategory($this->indexedCopy($ticket->getCategory(), Category::class));
        $ticket->setOneOffEnvelope($this->indexedCopy($hellfest, Envelope::class));

        self::getContainer()->get(UpdateTransactionProcessor::class)
            ->process($ticket, new Patch(), [], ['previous_data' => $previous]);

        $em->clear();
        $stored = $em->find(Transaction::class, $this->getFixture('ticket')->getId());
        self::assertSame('Hellfest 2026', $stored->getOneOffEnvelope()?->getName());
        self::assertSame('Loisirs', $stored->getCategory()?->getName());
    }
}
