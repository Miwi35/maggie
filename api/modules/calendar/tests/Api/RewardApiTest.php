<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Reward;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Service\RewardMessages;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * The bravo of a task done (MAG-508): a tick lays down one reward, a star,
 * whichever door ticked; its message never repeats the previous one; the
 * rare surprise follows its rules, one a day; reopening takes nothing back.
 */
class RewardApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const string TODAY = '2026-10-14';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->client->disableReboot();
        Clock::set(new MockClock(self::TODAY.' 12:00:00 UTC'));
        $this->loadFixtures('RewardApiTest.yaml');
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
    private function rewardOf(string $ref): array
    {
        $reward = $this->request('GET', '/api/rewards/task/'.$this->id($ref));
        self::assertResponseIsSuccessful();
        \assert(\is_array($reward));

        return $reward;
    }

    private function setDay(): void
    {
        $this->request('PUT', '/api/day-plans/'.self::TODAY, [
            'slots' => array_map(fn (string $ref): array => ['type' => 'task', 'taskId' => $this->id($ref)], ['t1', 't2', 't3', 't4', 't5']),
            'confirm' => true,
        ]);
        self::assertResponseIsSuccessful();
    }

    private function user(): User
    {
        $this->em()->clear();

        return $this->em()->find(User::class, $this->getFixture('test_user')->getId());
    }

    /** @return list<Reward> */
    private function storedRewards(): array
    {
        return $this->em()->getRepository(Reward::class)->findBy(['user' => $this->user()], ['earnedAt' => 'ASC']);
    }

    private function setTone(string $tone): void
    {
        $this->request('PUT', '/api/task-settings/me', ['tone' => $tone]);
        self::assertResponseIsSuccessful();
    }

    /** A star earned on another day, for the months before. */
    private function pastStar(string $earnedAt, string $subjectId): void
    {
        $this->em()->persist((new Reward())
            ->setUser($this->user())
            ->setSubjectType(Reward::SUBJECT_TASK)
            ->setSubjectId($subjectId)
            ->setMessageKey('doux.1')
            ->setMessage(RewardMessages::TONES['doux'][0])
            ->setEarnedAt(new \DateTimeImmutable($earnedAt)));
        $this->em()->flush();
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', '/api/rewards/task/'.$this->id('t3'));

        self::assertResponseStatusCodeSame(401);
    }

    public function testAnotherUsersRewardIsNotFound(): void
    {
        $this->request('GET', '/api/rewards/task/01JZ0THERS0000000000000000');
        self::assertResponseStatusCodeSame(404);

        $this->request('GET', '/api/rewards/task/'.$this->id('t3'));
        self::assertResponseStatusCodeSame(404, 'a task not done has no reward');
    }

    public function testAnUnknownSubjectTypeIsA400(): void
    {
        $data = $this->request('GET', '/api/rewards/chore/'.$this->id('t3'));

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('subjectType', (string) $data['error']);
    }

    public function testTickingATaskLaysDownAStarWithAMessageOfTheGentleToneAndPublishesIt(): void
    {
        $this->tick('t3');

        $rewards = $this->storedRewards();
        self::assertCount(1, $rewards);
        $stored = $rewards[0];
        self::assertSame(Reward::SUBJECT_TASK, $stored->getSubjectType());
        self::assertSame($this->id('t3'), $stored->getSubjectId());
        self::assertTrue($stored->isStar(), 'a reward with a star is a star of the constellation');
        self::assertSame(Reward::LEVEL_STANDARD, $stored->getLevel());
        self::assertNull($stored->getSurprise());
        self::assertMatchesRegularExpression('/^doux\.([1-9]|10)$/', $stored->getMessageKey(), '« doux » before any setting');
        self::assertContains($stored->getMessage(), RewardMessages::TONES['doux']);
        self::assertSame('2026-10-14T12:00:00+00:00', $stored->getEarnedAt()->format(\DATE_ATOM));

        $reward = $this->rewardOf('t3');
        self::assertSame([
            'id' => (string) $stored->getId(),
            'subjectType' => 'task',
            'subjectId' => $this->id('t3'),
            'level' => 'standard',
            'surprise' => null,
            'messageKey' => $stored->getMessageKey(),
            'message' => $stored->getMessage(),
            'star' => true,
            'earnedAt' => '2026-10-14T12:00:00+00:00',
        ], $reward);

        $topic = '/api/rewards/'.$stored->getId();
        $this->assertMercurePublishedOnce($topic);
        self::assertSame($stored->getMessage(), $this->mercurePayloadsOn($topic)[0]['message']);
        $this->assertMercureUpdatePublished('/api/tasks/'.$this->id('t3'));
        // The rewards are not searched: nothing is indexed.
        $this->assertNoElasticsearchIndexDispatched(Reward::class);
    }

    public function testReopeningKeepsTheStarAndTickingAgainLaysDownNoOther(): void
    {
        $this->tick('t3');
        $first = $this->rewardOf('t3');

        $this->tick('t3', false);
        self::assertSame($first, $this->rewardOf('t3'), 'a star is never lost');
        $this->tick('t3');

        self::assertCount(1, $this->storedRewards());
        self::assertSame($first, $this->rewardOf('t3'));
    }

    public function testTheToneSetPicksTheBank(): void
    {
        $this->setTone('drole');

        $this->tick('t3');

        $reward = $this->rewardOf('t3');
        self::assertStringStartsWith('drole.', $reward['messageKey']);
        self::assertContains($reward['message'], RewardMessages::TONES['drole']);
    }

    public function testTwoTicksInARowNeverShowTheSameMessage(): void
    {
        $this->setTone('direct');
        $keys = [];
        foreach (['t3', 't4', 't5', 'spare1', 'spare2', 'spare3'] as $ref) {
            $this->tick($ref);
            $keys[] = $this->rewardOf($ref)['messageKey'];
        }

        foreach (array_slice($keys, 1) as $index => $key) {
            self::assertNotSame($keys[$index], $key, 'the same message twice in a row');
        }
    }

    public function testTheLastOfTheDayIsAShootingStarAndASecondSurpriseTheSameDayIsStandard(): void
    {
        $this->setDay();

        $this->tick('t3');
        $this->tick('t4');
        self::assertNull($this->rewardOf('t4')['surprise'], 'four out of five: not yet');
        $this->tick('t5');

        $last = $this->rewardOf('t5');
        self::assertSame('surprise', $last['level']);
        self::assertSame('fullDay', $last['surprise']);
        self::assertMatchesRegularExpression('/^fullDay\.[1-3]$/', $last['messageKey']);
        self::assertContains($last['message'], RewardMessages::SURPRISES['fullDay']);
        self::assertStringStartsWith('Étoile filante', $last['message']);

        $this->tick('waiting');

        $sameDay = $this->rewardOf('waiting');
        self::assertSame('standard', $sameDay['level'], 'one surprise a day at most');
        self::assertNull($sameDay['surprise']);
    }

    public function testATaskPutOffForFourDaysIsAShootingStarWithItsDays(): void
    {
        $this->tick('waiting');

        $reward = $this->rewardOf('waiting');
        self::assertSame('waited', $reward['surprise']);
        self::assertSame('surprise', $reward['level']);
        self::assertStringContainsString('4 jours', $reward['message']);
        self::assertStringNotContainsString('{jours}', $reward['message']);
        self::assertTrue($reward['star']);
    }

    public function testATaskPutOffForADayIsStandard(): void
    {
        $this->tick('yesterday');

        self::assertNull($this->rewardOf('yesterday')['surprise']);
    }

    public function testTheStarThatPassesTheBestMonthBeforeIsARecord(): void
    {
        // August: 1, September: 2 — the best month before is 2.
        $this->pastStar('2026-08-20 10:00 UTC', 'past-1');
        $this->pastStar('2026-09-03 10:00 UTC', 'past-2');
        $this->pastStar('2026-09-21 10:00 UTC', 'past-3');
        // October: 2 already, on other days.
        $this->pastStar('2026-10-02 10:00 UTC', 'past-4');

        $this->tick('spare1');
        self::assertNull($this->rewardOf('spare1')['surprise'], 'equal to the best month: no record');
        $this->tick('spare2');

        $record = $this->rewardOf('spare2');
        self::assertSame('monthRecord', $record['surprise']);
        self::assertStringContainsString('3', $record['message']);
        self::assertStringNotContainsString('{total}', $record['message']);
    }

    public function testNoRecordTheFirstMonth(): void
    {
        $this->pastStar('2026-10-02 10:00 UTC', 'past-1');

        $this->tick('spare1');

        self::assertNull($this->rewardOf('spare1')['surprise']);
    }
}
