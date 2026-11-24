<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Mcp\Tool\ClaimDueProactionsTool;
use Maggie\Calendar\Mcp\Tool\ScheduleProactionTool;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** The words a proaction was asked in, set by the agent's code and read by its checker (MAG-977). */
class ProactionOriginTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('ProactionOriginTest.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array<string, mixed> */
    private function call(string $tool, mixed ...$args): array
    {
        return json_decode(self::getContainer()->get($tool)(...$args), true, 512, JSON_THROW_ON_ERROR);
    }

    private function tomorrowAt(string $time): string
    {
        return (new \DateTimeImmutable('tomorrow '.$time, new \DateTimeZone('Europe/Paris')))->format('c');
    }

    private function stored(string $id): Proaction
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->find(Proaction::class, $id);
    }

    /** @return Proaction[] */
    private function proactions(): array
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Proaction::class)->findAll();
    }

    public function testScheduleKeepsTheOriginItIsGiven(): void
    {
        $this->loginFixtureUser();
        $origin = ['kind' => 'owner_message', 'ref' => 'm1'];

        $data = $this->call(ScheduleProactionTool::class, 'Ajoute du lait à la liste de courses', $this->tomorrowAt('08:30'), null, null, null, $origin);

        self::assertTrue($data['success'], json_encode($data, JSON_THROW_ON_ERROR));
        self::assertSame($origin, $data['proaction']['origin']);
        self::assertSame($origin, $this->stored($data['proaction']['id'])->getOrigin());
        $this->assertMercureUpdatePublished('/proactions/');
        $this->assertElasticsearchIndexDispatched(Proaction::class);
    }

    public function testAProactionWithoutOriginHasNone(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(ScheduleProactionTool::class, 'Rappelle le pain', $this->tomorrowAt('08:30'));

        self::assertTrue($data['success'], json_encode($data, JSON_THROW_ON_ERROR));
        self::assertArrayHasKey('origin', $data['proaction']);
        self::assertNull($data['proaction']['origin']);
        self::assertNull($this->stored($data['proaction']['id'])->getOrigin());
    }

    /** @return iterable<string, array{array<string, mixed>}> */
    public static function badOrigins(): iterable
    {
        yield 'kind alone' => [['kind' => 'owner_message']];
        yield 'ref alone' => [['ref' => 'm1']];
        yield 'unknown kind' => [['kind' => 'chat', 'ref' => 'm1']];
        yield 'empty ref' => [['kind' => 'owner_message', 'ref' => '']];
        yield 'ref too long' => [['kind' => 'owner_message', 'ref' => str_repeat('a', 65)]];
        yield 'another key' => [['kind' => 'owner_message', 'ref' => 'm1', 'by' => 'model']];
    }

    /** @param array<string, mixed> $origin */
    #[DataProvider('badOrigins')]
    public function testAPartialOrUnknownOriginIsRefusedAndNothingIsStored(array $origin): void
    {
        $this->loginFixtureUser();

        $data = $this->call(ScheduleProactionTool::class, 'Ajoute du lait', $this->tomorrowAt('08:30'), null, null, null, $origin);

        self::assertArrayHasKey('error', $data);
        self::assertStringContainsString('origin is {kind: owner_message|instruction, ref}', $data['error']);
        self::assertStringContainsString(json_encode($origin, JSON_UNESCAPED_UNICODE | JSON_THROW_ON_ERROR), $data['error']);
        self::assertCount(1, $this->proactions());
        $this->assertMercureUpdateCount(0);
    }

    public function testClaimReturnsTheOrigin(): void
    {
        // No user bound: the executor's service token.
        $data = $this->call(ClaimDueProactionsTool::class);

        self::assertSame(1, $data['count']);
        self::assertSame(['kind' => 'owner_message', 'ref' => '01JM0000000000000000000001'], $data['proactions'][0]['origin']);
    }
}
