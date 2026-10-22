<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SqlQueryCounter;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Contact;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Serializer\TransactionListNormalizer;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * `GET /api/transactions` costs the same number of SQL queries whatever the
 * size of the page, and never answers more than 100 lines (MAG-544).
 */
final class TransactionListPerformanceTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

    public function testTheNumberOfQueriesDoesNotGrowWithTheNumberOfLines(): void
    {
        $this->world(120);

        $small = $this->queriesFor('/api/transactions?itemsPerPage=5');
        $large = $this->queriesFor('/api/transactions?itemsPerPage=100');

        self::assertSame($small, $large, 'The page of 100 lines runs more queries than the page of 5: something is loaded line by line.');
    }

    public function testTheListIsWrittenByTheFastWriter(): void
    {
        $this->world(10);

        $body = $this->get('/api/transactions?itemsPerPage=5');

        $writer = self::getContainer()->get(TransactionListNormalizer::class);
        \assert($writer instanceof TransactionListNormalizer);
        self::assertCount(5, $body['member']);
        self::assertSame(5, $writer->written(), 'The list no longer goes through TransactionListNormalizer: its gain is lost.');
    }

    public function testAPageIsNeverLargerThanTheCap(): void
    {
        $this->world(120);

        $body = $this->get('/api/transactions?itemsPerPage=1000');

        self::assertCount(100, $body['member']);
        self::assertSame(160, $body['totalItems']);
    }

    public function testTheListCanBeNarrowedByDate(): void
    {
        $this->world(10);

        $body = $this->get('/api/transactions?bookedAt[after]=2026-10-04&bookedAt[before]=2026-10-06');

        self::assertSame(4, $body['totalItems']);
    }

    private function world(int $count): void
    {
        $this->loadFixtures(dirname(__DIR__).'/Controller/fixtures/account_incidents.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $user = $this->getFixture('test_user');
        $account = $this->getFixture('checking');
        $savings = $this->getFixture('savings');

        for ($i = 0; $i < $count; ++$i) {
            $category = (new Category())->setName('Catégorie '.$i)->setUser($user);
            $contact = (new Contact())->setName('Contact '.$i)->setUser($user);
            $em->persist($category);
            $em->persist($contact);

            $day = (new \DateTimeImmutable('2026-10-01'))->modify('+'.($i % 28).' days');
            $transaction = (new Transaction())
                ->setUser($user)
                ->setAccount($account)
                ->setCategory($category)
                ->setContact($contact)
                ->setAmountCents(-1000 - $i)
                ->setCurrency('EUR')
                ->setBookedAt($i < 10 ? new \DateTimeImmutable('2026-10-'.sprintf('%02d', $i + 1)) : $day->modify('+40 days'))
                ->setLabel('Ligne '.$i)
                ->setStatus(TransactionStatus::Spent);
            $em->persist($transaction);

            if (0 === $i % 3) {
                $other = (new Transaction())
                    ->setUser($user)
                    ->setAccount($savings)
                    ->setAmountCents(1000 + $i)
                    ->setCurrency('EUR')
                    ->setBookedAt($transaction->getBookedAt())
                    ->setLabel('Contrepartie '.$i)
                    ->setStatus(TransactionStatus::Spent);
                $em->persist($other);
                $transaction->markAsInternalTransfer($other, TransferSource::Auto);
            }
        }

        $this->flushWithoutTransactionEffects($em);
        $em->clear();
    }

    private function queriesFor(string $uri): int
    {
        $counter = self::getContainer()->get(SqlQueryCounter::class);
        \assert($counter instanceof SqlQueryCounter);

        $this->get($uri);
        $counter->reset();
        $this->get($uri);

        return $counter->count();
    }

    /** @return array<string, mixed> */
    private function get(string $uri): array
    {
        $this->client->request('GET', $uri, [], [], ['HTTP_ACCEPT' => 'application/ld+json'] + $this->authHeaders());
        self::assertResponseIsSuccessful();

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }
}
