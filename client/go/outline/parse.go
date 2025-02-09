// Copyright 2024 The Outline Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package outline

import (
	"encoding/json"
	"fmt"
	"strings"

	"github.com/Jigsaw-Code/outline-apps/client/go/outline/platerrors"
	"gopkg.in/yaml.v3"
)

type parseTunnelConfigRequest struct {
	Transport yaml.Node
	Error     *struct {
		Message string
		Details string
	}
}

type legacyShadowsocksConfig struct {
	Server     string `yaml:"server"`
	ServerPort uint   `yaml:"server_port"`
	Method     string `yaml:"method"`
	Password   string `yaml:"password"`
	Prefix     string `yaml:"prefix"`
}

// tunnelConfigJson must match the definition in config.ts.
type tunnelConfigJson struct {
	FirstHop  string `json:"firstHop"`
	Transport string `json:"transport"`
}

func doParseTunnelConfig(input string) *InvokeMethodResult {
	var transportConfigText string
	var transportConfigBytes []byte

	input = strings.TrimSpace(input)
	input = strings.ReplaceAll(input, "\\/", "/") // Unescape forward slashes as it is not required in YAML.
	// Input may be one of:
	// - ss:// link
	// - Legacy Shadowsocks JSON (parsed as YAML)
	// - New advanced YAML format
	if strings.HasPrefix(input, "ss://") {
		transportConfigText = input
	} else {
		// Parse as YAML.
		tunnelConfig := parseTunnelConfigRequest{}
		legacyConfig := legacyShadowsocksConfig{}

		if err := yaml.Unmarshal([]byte(input), &tunnelConfig); err != nil {
			return &InvokeMethodResult{
				Error: &platerrors.PlatformError{
					Code:    platerrors.InvalidConfig,
					Message: fmt.Sprintf("failed to parse: %s", err),
				},
			}
		}

		// Process provider error, if present.
		if tunnelConfig.Error != nil {
			platErr := &platerrors.PlatformError{
				Code:    platerrors.ProviderError,
				Message: tunnelConfig.Error.Message,
			}
			if tunnelConfig.Error.Details != "" {
				platErr.Details = map[string]any{
					"details": tunnelConfig.Error.Details,
				}
			}
			return &InvokeMethodResult{Error: platErr}
		}
		var err error
		// Check if the input is a new-style YAML config by checking for the presence of a top-level "transport" key.
		if tunnelConfig.Transport.IsZero() {
			// Try Legacy Shadowsocks JSON format.
			if err := yaml.Unmarshal([]byte(input), &legacyConfig); err != nil {
				return &InvokeMethodResult{
					Error: &platerrors.PlatformError{
						Code:    platerrors.InvalidConfig,
						Message: fmt.Sprintf("failed to parse: %s", err),
					},
				}
			}
			transportConfigBytes, err = yaml.Marshal(legacyConfig)
		} else {
			transportConfigBytes, err = yaml.Marshal(tunnelConfig.Transport)
		}

		if err != nil {
			return &InvokeMethodResult{
				Error: &platerrors.PlatformError{
					Code:    platerrors.InvalidConfig,
					Message: fmt.Sprintf("failed normalize config: %s", err),
				},
			}
		}
		transportConfigText = string(transportConfigBytes)
	}
	result := NewClient(transportConfigText)
	if result.Error != nil {
		return &InvokeMethodResult{
			Error: result.Error,
		}
	}
	streamFirstHop := result.Client.sd.ConnectionProviderInfo.FirstHop
	packetFirstHop := result.Client.pl.ConnectionProviderInfo.FirstHop
	response := tunnelConfigJson{Transport: transportConfigText}
	if streamFirstHop == packetFirstHop {
		response.FirstHop = streamFirstHop
	}
	responseBytes, err := json.Marshal(response)
	if err != nil {
		return &InvokeMethodResult{
			Error: &platerrors.PlatformError{
				Code:    platerrors.InternalError,
				Message: fmt.Sprintf("failed to serialize JSON response: %v", err),
			},
		}
	}

	return &InvokeMethodResult{
		Value: string(responseBytes),
	}
}
