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
	"testing"

	"github.com/Jigsaw-Code/outline-apps/client/go/outline/platerrors"
	"github.com/stretchr/testify/require"
)

func Test_doParseTunnelConfig(t *testing.T) {
	result := doParseTunnelConfig(`
transport:
  $type: tcpudp
  tcp: &shared
    $type: shadowsocks
    endpoint: example.com:80
    cipher: chacha20-ietf-poly1305
    secret: SECRET
  udp: *shared`)

	require.Nil(t, result.Error)
	require.Equal(t,
		"{\"firstHop\":\"example.com:80\",\"transport\":\"$type: tcpudp\\ntcp: \\u0026shared\\n    $type: shadowsocks\\n    endpoint: example.com:80\\n    cipher: chacha20-ietf-poly1305\\n    secret: SECRET\\nudp: *shared\\n\"}",
		result.Value)
}

func Test_doParseTunnelConfigLegacyJson(t *testing.T) {
	config := `{
    "server": "example.com",
    "server_port": 4321,
    "method": "chacha20-ietf-poly1305",
    "password": "SECRET",
	"prefix": "POST "
}`
	result := doParseTunnelConfig(config)

	require.Nil(t, result.Error)
	require.Equal(t, "{\"firstHop\":\"example.com:4321\",\"transport\":\"server: example.com\\nserver_port: 4321\\nmethod: chacha20-ietf-poly1305\\npassword: SECRET\\nprefix: 'POST '\\n\"}", result.Value)
}

func Test_doParseTunnelConfigLegacyYaml(t *testing.T) {
	config := `
server: example.com
server_port: 4321
method: chacha20-ietf-poly1305
password: SECRET
prefix: "POST "
`
	result := doParseTunnelConfig(config)

	require.Nil(t, result.Error)
	require.Equal(t, "{\"firstHop\":\"example.com:4321\",\"transport\":\"server: example.com\\nserver_port: 4321\\nmethod: chacha20-ietf-poly1305\\npassword: SECRET\\nprefix: 'POST '\\n\"}", result.Value)

}

func Test_doParseTunnelConfig_ProviderError(t *testing.T) {
	result := doParseTunnelConfig(`
error:
  message: Unauthorized
  details: Account expired
`)

	require.Equal(t, &platerrors.PlatformError{
		Code:    platerrors.ProviderError,
		Message: "Unauthorized",
		Details: map[string]any{
			"details": "Account expired",
		},
	}, result.Error)
}

func Test_doParseTunnelConfig_ProviderErrorUTF8(t *testing.T) {
	result := doParseTunnelConfig(`
error:
  message: "\u26a0 Invalid Access Key \/ Key \u1000\u102d\u102f\u1015\u103c\u1014\u103a\u101c\u100a\u103a\u1005\u1005\u103a\u1006\u1031\u1038\u1015\u1031\u1038\u1015\u102b\u104b"
  details: "\u26a0 Details \/ Key \u1000\u102d\u102f\u1015\u103c\u1014\u103a\u101c\u100a\u103a\u1005\u1005\u103a\u1006\u1031\u1038\u1015\u1031\u1038\u1015\u102b\u104b"
`)

	require.Equal(t, &platerrors.PlatformError{
		Code:    platerrors.ProviderError,
		Message: "⚠ Invalid Access Key / Key ကိုပြန်လည်စစ်ဆေးပေးပါ။",
		Details: map[string]any{
			"details": "⚠ Details / Key ကိုပြန်လည်စစ်ဆေးပေးပါ။",
		},
	}, result.Error)
}
