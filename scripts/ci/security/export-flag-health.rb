#!/usr/bin/env ruby
# frozen_string_literal: true

require 'yaml'
require 'json'
require 'time'
require 'fileutils'

ROOT_DIR = File.expand_path('../../..', __dir__)
REPORT_DIR = File.join(ROOT_DIR, 'test-results/security')
OUTPUT_FILE = File.join(REPORT_DIR, 'flag-health.json')
YAML_CANDIDATES = [
  File.join(ROOT_DIR, 'docs/04-operations/assets/unleash/feature-flags.yaml'),
  File.join(ROOT_DIR, 'docs/legacy/root/04-operations/assets/unleash/feature-flags.yaml')
].freeze

yaml_path = YAML_CANDIDATES.find { |candidate| File.exist?(candidate) }

unless yaml_path
  warn "[flag-health] manifest not found. checked:\n#{YAML_CANDIDATES.join("\n")}"
  exit 1
end

FileUtils.mkdir_p(REPORT_DIR)

documents = YAML.load_stream(File.read(yaml_path)).compact

flags = documents.map do |doc|
  metadata = doc['metadata'] || {}
  annotations = metadata['annotations'] || {}
  spec = doc['spec'] || {}

  {
    id: annotations['mp/flag-id'],
    name: metadata['name'],
    owner: annotations['mp/flag-owner'],
    lifecycle: annotations['mp/flag-lifecycle'],
    created_at: annotations['mp/flag-created'],
    runbook: annotations['mp/runbook'],
    tags: spec['tags'] || [],
    guardrails: spec['guardrails'] || [],
    environments: (spec['environments'] || []).map do |env|
      {
        name: env['name'],
        default: env['default'],
        rollout: env['rollout']
      }
    end,
    kill_switch: spec.dig('killSwitch', 'linkedFlag')
  }
end

report = {
  generated_at: Time.now.utc.iso8601,
  manifest_source: yaml_path.sub("#{ROOT_DIR}/", ''),
  total_flags: flags.length,
  kill_switch_flags: flags.count { |flag| flag[:lifecycle] == 'kill-switch' },
  stale_candidates: flags.select { |flag| flag[:lifecycle] == 'ga' && flag[:tags].include?('stale') }.map { |flag| flag[:id] },
  flags: flags
}

File.write(OUTPUT_FILE, JSON.pretty_generate(report))
puts "[flag-health] Report written to #{OUTPUT_FILE}"
