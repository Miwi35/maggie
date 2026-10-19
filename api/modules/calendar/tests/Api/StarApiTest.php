<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Reward;
use Maggie\Calendar\Service\RewardGranter;
use Maggie\Calendar\Service\RewardMessages;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * The constellation of the month (MAG-509): one star per task done, the totals
 * only go up, a month passed keeps its count; and the palier, a reward with no
 * star that the Sagas lot lays down at the end of a chapter.
 */
class StarApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->client->disableReboot();
        Clock::set(new MockClock('2026-10-14 12:00:00 UTC'));
        $this->loadFixtures('StarApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    private function user(string $ref = 'test_user'): User
    {
        $this->em()->clear();

        return $this->em()->find(User::class, $this->getFixture($ref)->getId());
    }

    /** @return array<string, mixed>|null */
    private function request(string $method, string $endpoint, mixed $body = null, string $contentType = 'application/json'): ?array
    {
        $this->client->request($method, $endpoint, [], [], $this->authHeaders() + ['CONTENT_TYPE' => $contentType, 'HTTP_ACCEPT' => 'application/ld+json'], null === $body ? null : json_encode($body, JSON_THROW_ON_ERROR));

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function tick(string $ref, bool $done = true): void
    {
        $this->request('PATCH', '/api/tasks/'.$this->id($ref), ['completedAt' => $done ? Clock::get()->now()->format(\DATE_ATOM) : null], 'application/merge-patch+json');
        self::assertResponseIsSuccessful();
    }

    /** @return array<string, mixed> */
    private function stars(string $query = ''): array
    {
        $this->client->request('GET', '/api/stars'.$query, [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function star(string $earnedAt, string $subjectId, string $userRef = 'test_user'): void
    {
        $this->em()->persist((new Reward())
            ->setUser($this->user($userRef))
            ->setSubjectType(Reward::SUBJECT_TASK)
            ->setSubjectId($subjectId)
            ->setMessageKey('doux.1')
            ->setMessage(RewardMessages::TONES['doux'][0])
            ->setEarnedAt(new \DateTimeImmutable($earnedAt)));
        $this->em()->flush();
    }

    private function granter(): RewardGranter
    {
        return new RewardGranter(self::getContainer()->get('messenger.bus.default'));
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', '/api/stars');

        self::assertResponseStatusCodeSame(401);
    }

    /** @return iterable<string, array{string}> */
    public static function invalidMonths(): iterable
    {
        yield 'month 13' => ['2026-13'];
        yield 'month 00' => ['2026-00'];
        yield 'one digit' => ['2026-1'];
        yield 'a word' => ['octobre'];
        yield 'a day' => ['2026-10-14'];
    }

    #[\PHPUnit\Framework\Attributes\DataProvider('invalidMonths')]
    public function testAnInvalidMonthIsA400(string $month): void
    {
        $this->request('GET', '/api/stars?month='.$month);

        self::assertResponseStatusCodeSame(400);
    }

    public function testTwelveTicksAndThreeReopeningsCountTwelveInOctoberAndTheyStayInNovember(): void
    {
        for ($i = 1; $i <= 12; ++$i) {
            $this->tick('t'.$i);
        }
        foreach (['t1', 't2', 't3'] as $ref) {
            $this->tick($ref, false);
        }

        self::assertCount(12, $this->em()->getRepository(Reward::class)->findBy(['user' => $this->user(), 'star' => true]));
        $october = $this->stars();
        self::assertSame('2026-10', $october['month'], 'the current month by default');
        self::assertSame(12, $october['count']);
        self::assertSame(12, $october['total']);
        self::assertNull($october['best'], 'no month before this one');
        self::assertSame('2026-10-14T12:00:00+00:00', $october['latestAt']);

        // Ticking a reopened task again lays down no other star.
        $this->tick('t1');
        self::assertSame(12, $this->stars()['count']);

        Clock::set(new MockClock('2026-11-03 09:00:00 UTC'));
        $november = $this->stars();
        self::assertSame('2026-11', $november['month']);
        self::assertSame(0, $november['count']);
        self::assertSame(12, $november['total'], 'the total counts the months passed');
        self::assertSame(['month' => '2026-10', 'count' => 12], $november['best']);
        self::assertNull($november['latestAt']);

        $back = $this->stars('?month=2026-10');
        self::assertSame(12, $back['count'], 'a month passed keeps its count');
        self::assertSame(12, $back['total']);
        self::assertNull($back['best']);
    }

    public function testAMonthWithoutStarCountsZeroWithoutError(): void
    {
        $this->star('2026-10-05 08:00 UTC', 'past-1');

        $empty = $this->stars('?month=2026-02');

        self::assertSame(['month' => '2026-02', 'count' => 0, 'total' => 1, 'best' => null, 'latestAt' => null], $empty);
    }

    public function testBestIsTheBestMonthBeforeTheOneAsked(): void
    {
        $this->star('2026-08-03 08:00 UTC', 'a1');
        $this->star('2026-08-04 08:00 UTC', 'a2');
        foreach (['b1', 'b2', 'b3'] as $id) {
            $this->star('2026-09-10 08:00 UTC', $id);
        }
        $this->star('2026-10-02 08:00 UTC', 'c1');

        self::assertSame(['month' => '2026-09', 'count' => 3], $this->stars('?month=2026-10')['best']);
        self::assertSame(['month' => '2026-08', 'count' => 2], $this->stars('?month=2026-09')['best']);
        self::assertNull($this->stars('?month=2026-08')['best']);
        self::assertSame(6, $this->stars('?month=2026-08')['total']);
    }

    public function testMonthsAreTheUsersOwnInTheirTimezone(): void
    {
        // 23:30 UTC on 30 September is 01:30 on 1 October in Paris.
        $this->star('2026-09-30 23:30 UTC', 'edge');

        self::assertSame(1, $this->stars('?month=2026-10')['count']);
        self::assertSame(0, $this->stars('?month=2026-09')['count']);
    }

    public function testAnotherUsersStarsAreNotMine(): void
    {
        $this->star('2026-10-05 08:00 UTC', 'theirs', 'other_user');
        $this->star('2026-10-06 08:00 UTC', 'mine');

        $stars = $this->stars();

        self::assertSame(1, $stars['count']);
        self::assertSame(1, $stars['total']);
    }

    public function testAPalierIsARewardWithoutStarOfTheTonesBankAndIsPublished(): void
    {
        $this->request('PUT', '/api/task-settings/me', ['tone' => 'direct']);
        self::assertResponseIsSuccessful();
        $this->tick('t1');
        $this->resetMercure();
        $chapter = '01JZ0CHAPTER00000000000000';

        $reward = $this->granter()->grantPalier($this->user(), Reward::SUBJECT_SAGA_CHAPTER, $chapter, 'Le chapitre du port');

        $stored = $this->em()->getRepository(Reward::class)->findOneBy(['subjectType' => 'sagaChapter', 'subjectId' => $chapter]);
        self::assertInstanceOf(Reward::class, $stored);
        self::assertTrue($reward->getId()->equals($stored->getId()));
        self::assertSame(Reward::LEVEL_PALIER, $stored->getLevel());
        self::assertFalse($stored->isStar());
        self::assertNull($stored->getSurprise());
        self::assertSame('Le chapitre du port', $stored->getTitle());
        self::assertMatchesRegularExpression('/^palier-direct\.[1-3]$/', $stored->getMessageKey());
        self::assertContains($stored->getMessage(), RewardMessages::PALIERS['direct']);
        self::assertSame('2026-10-14T12:00:00+00:00', $stored->getEarnedAt()->format(\DATE_ATOM));

        $topic = '/api/rewards/'.$stored->getId();
        $this->assertMercurePublishedOnce($topic);
        $payload = $this->mercurePayloadsOn($topic)[0];
        self::assertSame('palier', $payload['level']);
        self::assertSame('Le chapitre du port', $payload['title']);
        self::assertFalse($payload['star']);

        $read = $this->request('GET', '/api/rewards/sagaChapter/'.$chapter);
        self::assertResponseIsSuccessful();
        self::assertSame('palier', $read['level']);
        self::assertSame('Le chapitre du port', $read['title']);

        $stars = $this->stars();
        self::assertSame(1, $stars['count'], 'a palier is not a star');
        self::assertSame(1, $stars['total']);
    }

    public function testAPalierIsGrantedOncePerSubjectAndNeverRepeatsTheLastMessage(): void
    {
        $granter = $this->granter();
        $first = $granter->grantPalier($this->user(), Reward::SUBJECT_SAGA_CHAPTER, 'chapter-1', 'Un');
        $again = $granter->grantPalier($this->user(), Reward::SUBJECT_SAGA_CHAPTER, 'chapter-1', 'Un');
        self::assertTrue($first->getId()->equals($again->getId()));

        $second = $granter->grantPalier($this->user(), Reward::SUBJECT_SAGA_CHAPTER, 'chapter-2', 'Deux');
        self::assertNotSame($first->getMessageKey(), $second->getMessageKey());
        self::assertStringStartsWith('palier-doux.', $second->getMessageKey(), '« doux » before any setting');
    }

    public function testAPalierRefusesAnUnknownSubjectType(): void
    {
        $this->expectException(\InvalidArgumentException::class);

        $this->granter()->grantPalier($this->user(), 'chore', 'x', 'Titre');
    }
}
