<?php

declare(strict_types=1);

namespace App\Tests\Contract;

use Mcp\Capability\RegistryInterface;
use Mcp\Schema\Tool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Yaml\Yaml;

/**
 * The tool list the agent sees, frozen into contract/mcp-tools.json.
 *
 * The agent calls these tools by name and fills their arguments from the
 * schema; nothing in PHP breaks when a tool is renamed or an argument
 * disappears. The failure surfaces as Maggie saying she cannot do something
 * she could do yesterday.
 *
 * The sharpest case is silent and has its own line in CLAUDE.md: a module
 * missing from `discovery.scan_dirs` in config/packages/mcp.yaml has all of
 * its tools hidden, with no error anywhere. Snapshotting the registry catches
 * that as forty-odd deleted lines in the diff.
 */
final class McpToolsContractTest extends KernelTestCase
{
    use ContractSnapshotTrait;

    /**
     * Every module that owns at least one tool. A bundle whose directory
     * drops out of `discovery.scan_dirs` loses its tools without a word, so
     * the presence of each module is asserted by name and not only through
     * the snapshot — the snapshot says "something changed", this says which
     * module went dark.
     */
    private const MODULE_TOOLS = [
        'calendar' => 'create_event',
        'cookbook' => 'create_recipe',
        'core' => 'search',
        'finance' => 'manage_accounts',
        'grocery' => 'add_grocery_item',
        'notification' => 'manage_notifications',
    ];

    public function testTheToolListMatchesTheContract(): void
    {
        $this->assertMatchesContract(
            'mcp-tools.json',
            $this->tools(),
            'A tool was added, renamed or removed, or one of their argument schemas changed. The agent addresses tools by name and fills arguments from these schemas, so check agent/ before regenerating.',
        );
    }

    public function testEveryModuleStillContributesItsTools(): void
    {
        $names = array_keys($this->tools());

        foreach (self::MODULE_TOOLS as $module => $tool) {
            self::assertContains($tool, $names, sprintf(
                'No tool named "%s" was discovered, which means the %s module contributes nothing. Check that "modules/%s/src" is still listed under discovery.scan_dirs in config/packages/mcp.yaml.',
                $tool,
                $module,
                $module,
            ));
        }
    }

    /**
     * The MCP SDK pages tools/list at `pagination_limit` (50 by default) and
     * hands back a nextCursor. The agent and the smoke journey read the first
     * page only, so the tool past the limit vanishes without an error: Maggie
     * simply cannot do something she could do before.
     */
    public function testTheWholeToolListFitsInOnePage(): void
    {
        $config = Yaml::parseFile(self::getContainer()->getParameter('kernel.project_dir').'/config/packages/mcp.yaml');
        $limit = $config['mcp']['pagination_limit'] ?? 50;
        $count = \count($this->tools());

        self::assertGreaterThan($count, $limit, sprintf(
            'mcp.pagination_limit is %d for %d tools: tools/list would split into pages and the agent only reads the first. Raise pagination_limit in config/packages/mcp.yaml.',
            $limit,
            $count,
        ));
    }

    /**
     * A tool with no description is a tool the agent will not reach for, and
     * an argument schema without properties means the agent has to guess.
     * Neither breaks a PHP test; both break Maggie.
     */
    public function testEveryToolIsDescribedAndTakesADeclaredSchema(): void
    {
        foreach ($this->tools() as $name => $tool) {
            self::assertNotSame('', trim((string) ($tool['description'] ?? '')), sprintf('The MCP tool "%s" has no description.', $name));
            self::assertSame('object', $tool['inputSchema']['type'] ?? null, sprintf('The MCP tool "%s" does not declare an object input schema.', $name));
        }
    }

    /**
     * A description that sends the agent to a tool that does not exist makes
     * it call the void, then apologise. A snake_case word that starts like a tool
     * name (manage_, create_, get_…) must be a tool name or an argument name;
     * other words (enum values such as no_budget) are left alone.
     */
    public function testNoDescriptionPointsToAMissingTool(): void
    {
        $tools = $this->tools();

        foreach ($tools as $name => $tool) {
            self::assertSame([], self::unknownReferences((string) $tool['description'], $tools), sprintf(
                'The description of "%s" cites a name that looks like a tool but is neither a tool nor an argument. Fix the description (the tools are: %s).',
                $name,
                implode(', ', array_keys($tools)),
            ));
        }
    }

    public function testTheMissingToolDetectorCatchesAWrongName(): void
    {
        $tools = $this->tools();

        self::assertSame(['list_agendas'], self::unknownReferences('Use agenda_id (from list_agendas) or manage_agendas.', $tools));
        self::assertSame([], self::unknownReferences('Use agenda_id (from manage_agendas).', $tools));
    }

    public function testManageCategoriesDocumentsTheIncomeFlag(): void
    {
        self::assertStringContainsString('income', (string) $this->tools()['manage_categories']['description']);
    }

    /**
     * @param array<string, array<string, mixed>> $tools
     *
     * @return list<string>
     */
    private static function unknownReferences(string $description, array $tools): array
    {
        $known = array_keys($tools);
        // "list" prefixes no tool today, but it is the verb an agent invents first.
        $verbs = array_unique([...array_map(static fn (string $name): string => explode('_', $name)[0], $known), 'list']);
        foreach ($tools as $tool) {
            array_push($known, ...array_keys($tool['inputSchema']['properties'] ?? []));
        }

        preg_match_all('/\b[a-z]+(?:_[a-z]+)+\b/', $description, $matches);

        $candidates = array_filter($matches[0], static fn (string $word): bool => \in_array(explode('_', $word)[0], $verbs, true));

        return array_values(array_unique(array_diff($candidates, $known)));
    }

    /**
     * @return array<string, array<string, mixed>> tool name => tools/list entry
     */
    private function tools(): array
    {
        $container = self::getContainer();

        // Building the server is what runs the discovery loaders; asking the
        // registry first returns an empty list, which would make this whole
        // file pass against nothing.
        $container->get('mcp.server');

        $registry = $container->get('mcp.registry');
        self::assertInstanceOf(RegistryInterface::class, $registry);

        $tools = [];
        foreach ($registry->getTools()->references as $reference) {
            $tool = $reference instanceof Tool ? $reference : $reference->tool;
            $encoded = json_decode(json_encode($tool, \JSON_THROW_ON_ERROR), true, 512, \JSON_THROW_ON_ERROR);
            self::assertIsArray($encoded);

            $tools[$tool->name] = $encoded;
        }

        self::assertNotEmpty($tools, 'The MCP registry discovered no tool at all.');

        // The registry iterates in discovery order, which depends on the order
        // the filesystem hands back directory entries. Sorting keeps the
        // contract about the tools rather than about the machine that ran the
        // test.
        ksort($tools);

        return $tools;
    }
}
